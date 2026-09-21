# P1 + 批次 4 的接口级真机验收
# 非破坏性（除最后一节 P1-B3 会改 student01 的密码，重启后端即随 H2 内存库回滚）
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api/v1'
$script:pass = 0; $script:fail = 0

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; "PASS  $name" }
  else { $script:fail++; "FAIL  $name  -> $detail" }
}

function Req($method, $url, $token, $obj) {
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  $args = @{
    Uri = $url; Method = $method; Headers = $headers
    UseBasicParsing = $true; TimeoutSec = 15; SkipHttpErrorCheck = $true
  }
  if ($null -ne $obj) {
    $args['Body'] = ($obj | ConvertTo-Json -Compress)
    $args['ContentType'] = 'application/json'
  }
  $r = Invoke-WebRequest @args
  return @{ status = [int]$r.StatusCode; body = $r.Content }
}

function GetRaw($url, $token) { return Req 'GET' $url $token $null }
function PostRaw($url, $token, $obj) { return Req 'POST' $url $token $obj }
function PutRaw($url, $token, $obj) { return Req 'PUT' $url $token $obj }
function Code($raw) { return ($raw.body | ConvertFrom-Json).code }

function Login($u, $p) {
  $r = PostRaw "$base/auth/login" $null @{ username = $u; password = $p }
  if ((Code $r) -ne 200) { throw "登录失败 $u : $($r.body)" }
  return ($r.body | ConvertFrom-Json).data
}

"===== P1-B1 工单读接口属主校验（IDOR）====="
# data-demo.sql：1001 属 student01(id=3)；1003/1005/1009/1015 属 student02(id=20)；1009 指派 worker01(id=2)
$tStudent02 = (Login 'student02' 'password').accessToken
$tTeacher01 = (Login 'teacher01' 'password').accessToken
$tWorker01  = (Login 'worker01'  'password').accessToken
$tAdmin     = (Login 'admin'     'password').accessToken

foreach ($suffix in @('', '/timeline', '/evaluation-detail')) {
  $r = GetRaw "$base/work-orders/1001$suffix" $tStudent02
  Check "他人报修人读 1001$suffix -> 403" ($r.status -eq 403 -and (Code $r) -eq 403) "status=$($r.status) code=$(Code $r)"
}
$r = GetRaw "$base/work-orders/1001" $tTeacher01
Check "他人教工读 1001 -> 403" ($r.status -eq 403) "status=$($r.status)"

$r = GetRaw "$base/work-orders/1003" $tStudent02
Check "本人（student02）读自己的 1003 -> 200" ($r.status -eq 200 -and (Code $r) -eq 200) "status=$($r.status) code=$(Code $r)"
$r = GetRaw "$base/work-orders/1003/timeline" $tStudent02
Check "本人读自己 1003 时间轴 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = GetRaw "$base/work-orders/1001" $tAdmin
Check "平台管理读任意单 1001 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = GetRaw "$base/work-orders/1001/timeline" $tAdmin
Check "平台管理读任意单时间轴 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = GetRaw "$base/work-orders/1009" $tWorker01
Check "指派师傅（worker01）读名下 1009 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = GetRaw "$base/work-orders/1003" $tWorker01
Check "非指派师傅读 1003 -> 403" ($r.status -eq 403) "status=$($r.status)"
$r = GetRaw "$base/work-orders/999999" $tAdmin
Check "不存在工单仍报 2003" ((Code $r) -eq 2003) "code=$(Code $r)"

"===== P1-B4 登出与令牌吊销 ====="
$sess = Login 'logistics01' 'password'
$acc = $sess.accessToken; $ref = $sess.refreshToken
$r = GetRaw "$base/users/me" $acc
Check "登出前 /users/me -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = PostRaw "$base/auth/logout" $acc @{ refreshToken = $ref }
Check "登出 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = GetRaw "$base/users/me" $acc
Check "登出后同一 Access Token -> 401" ($r.status -eq 401) "status=$($r.status) body=$($r.body)"
$r = PostRaw "$base/auth/refresh-token" $null @{ refreshToken = $ref }
Check "登出后 Refresh Token 换票被拒（1005）" ((Code $r) -eq 1005) "code=$(Code $r)"
# 真正不带 body、也不带 Content-Type 的调用方（curl 就是这样）必须成功。
# 注意不能用 Invoke-WebRequest -Method POST：它会自动塞 application/x-www-form-urlencoded，
# 那属于「声明了不受支持的 Content-Type」，按 HTTP 语义回 415 才是正确行为（旧行为是 500）。
$curlOut = & curl.exe -s -X POST "$base/auth/logout" 2>&1
Check "无令牌、无 body 登出幂等 -> 200" (($curlOut | ConvertFrom-Json).code -eq 200) "body=$curlOut"
$r = PostRaw "$base/auth/logout" 'not-a-real-token' @{ refreshToken = 'also-fake' }
Check "坏令牌登出幂等 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
# 非 JSON 的 Content-Type 必须回 415，而不是以前那种 500「系统处理异常」
$form = Invoke-WebRequest -Uri "$base/auth/login" -Method Post -Body @{ username = 'admin'; password = 'password' } -UseBasicParsing -SkipHttpErrorCheck
Check "form-urlencoded 打 JSON 端点 -> 415（旧行为是 500）" ($form.StatusCode -eq 415 -and (($form.Content | ConvertFrom-Json).code) -eq 415) "status=$($form.StatusCode) body=$($form.Content)"

"===== 批次 4 审计词表 ====="
$allowed = @('REPORTER', 'WORKER', 'DISPATCHER', 'PLATFORM_ADMIN', 'SYSTEM')
$tl = (GetRaw "$base/work-orders/1015/timeline" $tAdmin).body | ConvertFrom-Json
$bad = @($tl.data | Where-Object { $allowed -notcontains $_.operatorRole })
Check "演示种子时间轴（1015，含返工/仲裁）全部落在新词表内" ($bad.Count -eq 0) ("越界值: " + (($bad | ForEach-Object { $_.operatorRole }) -join ','))
"     1015 时间轴角色: " + (($tl.data | ForEach-Object { $_.operatorRole } | Sort-Object -Unique) -join ',')

# 真实动作：student02 提单 -> SUBMIT 必须是 REPORTER（旧实现写的是组织条线 STUDENT）
$spaces = (GetRaw "$base/spatial/tree" $tStudent02).body | ConvertFrom-Json
function FirstLeaf($nodes) {
  foreach ($n in $nodes) {
    if ($n.children -and $n.children.Count -gt 0) { $leaf = FirstLeaf $n.children; if ($leaf) { return $leaf } }
    else { return $n }
  }
  return $null
}
$leaf = FirstLeaf $spaces.data
$cats = (GetRaw "$base/categories/tree" $tStudent02).body | ConvertFrom-Json
# 故障分类是两级树，只有第 2 级才可提单（后端按 eq 精确匹配）
$cat = $cats.data | ForEach-Object { $_.children } | Select-Object -First 1
$r = PostRaw "$base/work-orders/submit" $tStudent02 @{
  spaceId = $leaf.id; categoryId = $cat.id
  description = 'P1/批次4 真机验收探针：提单审计词表核对'
  appointmentPeriod = '上午08:30-11:30'; urgencyLevel = 1; applicantPhone = '13800000020'
}
Check "student02 提单 -> 200" ((Code $r) -eq 200) "code=$(Code $r) body=$($r.body)"
$newOrderId = ($r.body | ConvertFrom-Json).data.orderId
$tl2 = (GetRaw "$base/work-orders/$newOrderId/timeline" $tStudent02).body | ConvertFrom-Json
$submitLog = $tl2.data | Where-Object { $_.actionType -eq 'SUBMIT' } | Select-Object -Last 1
Check "新提单的 SUBMIT.operator_role == REPORTER" ($submitLog.operatorRole -eq 'REPORTER') "actual=$($submitLog.operatorRole)"

$r = PostRaw "$base/work-orders/$newOrderId/dispatch" $tAdmin @{ workerId = 2; dispatchRemark = 'P1/批次4 真机验收探针：人工指派' }
Check "人工指派 -> 200" ((Code $r) -eq 200) "code=$(Code $r) body=$($r.body)"
$tl3 = (GetRaw "$base/work-orders/$newOrderId/timeline" $tAdmin).body | ConvertFrom-Json
$dispLog = $tl3.data | Where-Object { $_.actionType -eq 'MANUAL_DISPATCH' } | Select-Object -Last 1
Check "人工指派的 operator_role == DISPATCHER（旧实现写操作人组织条线）" ($dispLog.operatorRole -eq 'DISPATCHER') "actual=$($dispLog.operatorRole)"

"===== P1-B3 首登强制改密（末节：会改 student01 密码，重启后端即回滚）====="
$s = Login 'student01' 'password'
$acc1 = $s.accessToken
Check "student01 登录返回 isFirstLogin=1" ($s.userInfo.isFirstLogin -eq 1) "isFirstLogin=$($s.userInfo.isFirstLogin)"
$r = GetRaw "$base/work-orders/my" $acc1
Check "首登未改密访问业务端点 -> code 1009" ((Code $r) -eq 1009) "status=$($r.status) code=$(Code $r)"
$r = GetRaw "$base/users/me" $acc1
Check "首登未改密仍可读本人资料 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = PostRaw "$base/auth/refresh-token" $null @{ refreshToken = $s.refreshToken }
Check "首登未改密仍可换票 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = PutRaw "$base/users/password/change" $acc1 @{ oldPassword = 'password'; newPassword = 'ProbePass@2026' }
Check "改密 -> 200 且返回新令牌" ((Code $r) -eq 200 -and ($r.body | ConvertFrom-Json).data.accessToken) "code=$(Code $r)"
$acc2 = ($r.body | ConvertFrom-Json).data.accessToken
$r = GetRaw "$base/work-orders/my" $acc2
Check "改密后的新令牌可正常访问业务端点 -> 200" ((Code $r) -eq 200) "code=$(Code $r)"
$r = GetRaw "$base/work-orders/my" $acc1
Check "旧令牌仍被拦（必须换票）-> 1009" ((Code $r) -eq 1009) "code=$(Code $r)"

""
"===== 汇总 ====="
"PASS=$script:pass FAIL=$script:fail"