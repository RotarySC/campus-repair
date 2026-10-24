#!/usr/bin/env pwsh
<#
.SYNOPSIS
    本地开发服务一键启停：后端 API（:8080）+ 前端门户（:3000）。

.DESCRIPTION
    把 README「安装与启动」里的手工步骤固定成一条命令，并补上就绪探测：

      1. 环境   把 JAVA_HOME / PATH 指到仓库内置 .tools 的 JDK 17 与 Maven（不改系统环境变量）
      2. 后端   server/ 下 mvn spring-boot:run（默认 local profile：内存 H2 + data-demo.sql 演示数据）
      3. 前端   pnpm run dev:admin（Vite :3000，/api 与 /uploads 反代到 :8080）
      4. 探测   后端以匿名 GET /api/v1/auth/register-org-options 返回 {code:200} 为就绪 —— 该端点查库取单位树，
                能一次证明「端口在听、应用已启动、库与种子数据可用」；前端以 :3000 返回含 id="app" 的
                index.html 为就绪。等不到就打印日志末尾并停掉本次拉起的进程，不留半启动状态。
      5. 收尾   打印访问地址、演示账号、日志路径与停止命令

    服务在后台运行（不占当前终端）。日志写 .scratch/logs/（backend.out.log / backend.err.log /
    frontend.out.log / frontend.err.log），PID 记到 .scratch/run/services.json 供 -Stop 兜底清理。

    端口已被本服务占用时按「已在运行」跳过，不重复拉起；被别的程序占用则直接报错并给出 PID 与停止办法。

.PARAMETER Profile
    local（默认）：内存 H2 + data-demo.sql，零外部依赖，进程退出即丢数据。
    dev：MySQL localhost:3306 + Redis localhost:6379（见 application-dev.yml），需先自行起库。

.PARAMETER Stop
    停止正占用 8080/3000 的进程树（连同 mvn / pnpm 包装进程），并清理状态文件与 PID 记录。

.PARAMETER BackendOnly
    只起后端。

.PARAMETER FrontendOnly
    只起前端。

.PARAMETER Install
    前端依赖（node_modules）缺失时先跑 pnpm install，而不是直接报错。

.PARAMETER TimeoutSeconds
    单个服务的就绪等待上限，默认 180 秒（首次启动要编译并解析依赖，通常 40~90 秒）。

.EXAMPLE
    pwsh scripts/start.ps1

.EXAMPLE
    pwsh scripts/start.ps1 -Profile dev

.EXAMPLE
    pwsh scripts/start.ps1 -BackendOnly

.EXAMPLE
    pwsh scripts/start.ps1 -Stop
#>
[CmdletBinding()]
param(
    [ValidateSet('local', 'dev')]
    [string]$Profile = 'local',
    [switch]$Stop,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$Install,
    [ValidateRange(10, 1800)]
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
# Invoke-WebRequest 默认会画进度条：就绪探测每 0.5 秒打一次，终端会被刷成满屏「正在读取 Web 响应流」
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $root '.scratch/run'
$logDir = Join-Path $root '.scratch/logs'
$stateFile = Join-Path $runDir 'services.json'
New-Item -ItemType Directory -Force -Path $runDir, $logDir | Out-Null

# Tomcat 的 tempDir 与 ImageIO 都读 java.io.tmpdir（Windows 上取 TEMP/TMP）。DSH 的文件沙箱
# 不允许子进程写系统 temp 时，应用会在刷新上下文阶段直接失败：
#   Unable to create tempDir → AccessDeniedException: C:\Users\...\AppData\Local\Temp\tomcat.8080.xxx
# 统一指到工作区内（.scratch/tmp，已在 .gitignore 的 .scratch/* 里），与 scripts/verify.ps1 同一套做法。
$buildTmp = Join-Path $root '.scratch/tmp'
New-Item -ItemType Directory -Force -Path $buildTmp | Out-Null
$env:TEMP = $buildTmp
$env:TMP = $buildTmp

# PS 5.1 没有 $IsWindows（它只跑在 Windows 上，按 true 处理）
$onWindows = ($null -eq $IsWindows) -or $IsWindows

$BackendPort = 8080
$FrontendPort = 3000
# 探针同时试 127.0.0.1 与 localhost：Spring Boot 绑 0.0.0.0（IPv4 可达），
# 而 Vite 默认只绑 ::1（本机实测 LocalAddress=::1），只打 127.0.0.1 会「连接被积极拒绝」。
# audience 必填（缺省会 400「注册口径必须指定所属条线」）——带 STUDENT 后该端点会查库取单位树，
# 因此 200 同时证明：端口在听、应用已启动、库已连通、种子数据已灌（local profile 的 data-demo）。
$backendProbeUrls = @(
    "http://127.0.0.1:$BackendPort/api/v1/auth/register-org-options?audience=STUDENT"
    "http://localhost:$BackendPort/api/v1/auth/register-org-options?audience=STUDENT"
)
$frontendProbeUrls = @(
    "http://localhost:$FrontendPort/"
    "http://127.0.0.1:$FrontendPort/"
)

function Write-Step { param([string]$Text) Write-Host ''; Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Note { param([string]$Text) Write-Host "    $Text" -ForegroundColor DarkGray }
function Write-Ok { param([string]$Text) Write-Host "    $Text" -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host "    $Text" -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host "    $Text" -ForegroundColor Red }

# ---------------------------------------------------------------- 端口与进程

function Get-PortOwnerPid {
    param([int]$Port)
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        try {
            $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($conn) { return [int]$conn.OwningProcess }
            return $null
        }
        catch { }
    }
    # 兜底：netstat -ano（非 Windows pwsh 或 Get-NetTCPConnection 不可用时）
    try {
        foreach ($line in (& netstat -ano -p tcp 2>$null)) {
            if ($line -match ":$Port\s" -and $line -match 'LISTENING\s+(\d+)\s*$') {
                return [int]$Matches[1]
            }
        }
    }
    catch { }
    return $null
}

function Test-PortListening {
    param([int]$Port)
    return ($null -ne (Get-PortOwnerPid -Port $Port))
}

function Get-ProcessLabel {
    param([int]$ProcessId)
    try { return (Get-Process -Id $ProcessId -ErrorAction Stop).ProcessName } catch { return '?' }
}

function Stop-ProcessTree {
    param([int]$ProcessId)
    if ($ProcessId -le 0) { return }
    if ($onWindows) {
        # mvn.cmd / pnpm.cmd 下挂着 java / node 子进程，必须整棵树杀
        & taskkill.exe /PID $ProcessId /T /F *> $null
        return
    }
    try { Stop-Process -Id $ProcessId -Force -ErrorAction Stop } catch { }
}

# ---------------------------------------------------------------- HTTP 探测

function Invoke-Probe {
    param([string[]]$Urls)
    foreach ($url in $Urls) {
        try {
            return Invoke-WebRequest -Uri $url -TimeoutSec 3 -ErrorAction Stop
        }
        catch { }
    }
    return $null
}

function Test-BackendReady {
    $r = Invoke-Probe -Urls $backendProbeUrls
    if ($null -eq $r) { return $false }
    return ($r.StatusCode -eq 200 -and [string]$r.Content -match '"code"\s*:\s*200')
}

function Test-FrontendReady {
    $r = Invoke-Probe -Urls $frontendProbeUrls
    if ($null -eq $r) { return $false }
    return ($r.StatusCode -eq 200 -and [string]$r.Content -match 'id="app"')
}

function Wait-UntilReady {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [Parameter(Mandatory)][int]$Seconds,
        [Parameter(Mandatory)][string]$Label
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $tick = 0
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        if (& $Condition) { $sw.Stop(); return $sw.Elapsed.TotalSeconds }
        Start-Sleep -Milliseconds 500
        $tick++
        if ($tick % 20 -eq 0) {
            Write-Note ("{0} … {1}s（上限 {2}s）" -f $Label, [int]$sw.Elapsed.TotalSeconds, $Seconds)
        }
    }
    $sw.Stop()
    return -1
}

function Get-LogTail {
    param([string]$Path, [int]$Lines = 20)
    if (-not (Test-Path $Path)) { return @() }
    if ((Get-Item $Path).Length -eq 0) { return @() }
    $tail = @(Get-Content -Path $Path -Tail $Lines -ErrorAction SilentlyContinue)
    # 子进程 stdout 走系统代码页（本机 zh-CN = GBK），JVM 的日志因此不是 UTF-8；
    # UTF-8 解出替换字符时按 ANSI 重读一次，否则中文全是乱码，日志等于没用。
    if (($tail -join "`n") -match [string][char]0xFFFD) {
        try { $tail = @(Get-Content -Path $Path -Tail $Lines -Encoding ansi -ErrorAction Stop) } catch { }
    }
    return $tail
}

function Show-LogTail {
    param([string]$Path, [int]$Lines = 20, [string]$Title)
    $tail = Get-LogTail -Path $Path -Lines $Lines
    if ($tail.Count -eq 0) { return }
    Write-Note ("{0}：{1}" -f $Title, $Path)
    $tail | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- 工具链与依赖

function Resolve-MavenCommand {
    $javaHome = Join-Path $root '.tools/jdk/jdk-17.0.2'
    if (Test-Path (Join-Path $javaHome 'bin/java.exe')) {
        $env:JAVA_HOME = $javaHome
        $env:PATH = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
    }
    else {
        Write-Warn "仓库内置 JDK 缺失，使用系统 java：先确认版本是 17"
    }

    $mavenBin = Join-Path $root '.tools/maven/apache-maven-3.9.16/bin'
    $bundled = Join-Path $mavenBin 'mvn.cmd'
    if (Test-Path $bundled) {
        $env:PATH = $mavenBin + [IO.Path]::PathSeparator + $env:PATH
        return $bundled
    }
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) { throw '找不到 mvn：仓库内置 Maven 缺失，PATH 里也没有（需 3.9+）' }
    return $mvn.Source
}

function Resolve-PnpmCommandLine {
    $cmd = Get-Command pnpm -CommandType Application, ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $cmd) { throw '找不到 pnpm（需 ≥ 9）：https://pnpm.io/installation' }
    $exe = $cmd.Source
    if ($exe -like '*.ps1') {
        # 本机 pnpm 由 pnpm.ps1 包装；CreateProcess 不能直接起 .ps1，优先用同名 .cmd
        $cmdPath = [IO.Path]::ChangeExtension($exe, '.cmd')
        if (Test-Path $cmdPath) { $exe = $cmdPath }
        else { return 'powershell -NoProfile -File "' + $exe + '" run dev:admin' }
    }
    return '"' + $exe + '" run dev:admin'
}

function Assert-FrontendDependencies {
    $needed = @('node_modules', 'apps/admin-web/node_modules', 'packages/shared/node_modules')
    $missing = @($needed | Where-Object { -not (Test-Path (Join-Path $root $_)) })
    if ($missing.Count -eq 0) { return }

    if (-not $Install) {
        throw ("前端依赖缺失（{0}）。先跑 pnpm install，或加 -Install 让本脚本代跑。" -f ($missing -join '、'))
    }

    Write-Note '前端依赖缺失，执行 pnpm install …'
    Push-Location $root
    try {
        & pnpm install
        if ($LASTEXITCODE -ne 0) { throw 'pnpm install 失败' }
    }
    finally { Pop-Location }
}

# ---------------------------------------------------------------- 启动

function New-AlreadyRunningEntry {
    param($Previous, [int]$Port)
    $entry = [ordered]@{ alreadyRunning = $true; port = $Port }
    # 重复执行时不抹掉上一次记下的 PID：端口占用者（java / node）与包装进程（cmd）不是同一个 PID
    if ($Previous -and $Previous.processId) {
        $entry.processId = [int]$Previous.processId
        $entry.log = $Previous.log
        $entry.errLog = $Previous.errLog
    }
    return $entry
}

function Start-BackendService {
    $mvnExe = Resolve-MavenCommand
    $env:SPRING_PROFILES_ACTIVE = $Profile

    $out = Join-Path $logDir 'backend.out.log'
    $err = Join-Path $logDir 'backend.err.log'
    Remove-Item $out, $err -Force -ErrorAction SilentlyContinue

    # mvn.cmd 是批处理：带输出重定向时走不了 ShellExecute，统一经 cmd /c 包一层
    $line = '"' + $mvnExe + '" spring-boot:run'
    $sp = @{
        FilePath               = $env:ComSpec
        ArgumentList           = @('/c', $line)
        WorkingDirectory       = Join-Path $root 'server'
        RedirectStandardOutput = $out
        RedirectStandardError  = $err
        PassThru               = $true
    }
    if ($onWindows) { $sp.WindowStyle = 'Hidden' }
    $p = Start-Process @sp

    return [pscustomobject]@{
        ProcessId = $p.Id
        Log       = $out
        ErrLog    = $err
        Command   = "mvn spring-boot:run（profile=$Profile）"
    }
}

function Start-FrontendService {
    $line = Resolve-PnpmCommandLine

    $out = Join-Path $logDir 'frontend.out.log'
    $err = Join-Path $logDir 'frontend.err.log'
    Remove-Item $out, $err -Force -ErrorAction SilentlyContinue

    $sp = @{
        FilePath               = $env:ComSpec
        ArgumentList           = @('/c', $line)
        WorkingDirectory       = $root
        RedirectStandardOutput = $out
        RedirectStandardError  = $err
        PassThru               = $true
    }
    if ($onWindows) { $sp.WindowStyle = 'Hidden' }
    $p = Start-Process @sp

    return [pscustomobject]@{
        ProcessId = $p.Id
        Log       = $out
        ErrLog    = $err
        Command   = 'pnpm run dev:admin'
    }
}

# ---------------------------------------------------------------- 停止

function Invoke-StopAll {
    Write-Step '停止本地开发服务'

    $candidates = [System.Collections.Generic.List[object]]::new()
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $owner = Get-PortOwnerPid -Port $port
        if ($null -ne $owner) {
            $candidates.Add([pscustomobject]@{
                    Port    = $port
                    ProcessId = [int]$owner
                    Name    = (Get-ProcessLabel -ProcessId ([int]$owner))
                    Source  = 'port'
                })
        }
    }

    # 端口已释放但包装进程还挂着时，靠状态文件兜底
    if (Test-Path $stateFile) {
        try {
            $state = Get-Content -Path $stateFile -Raw | ConvertFrom-Json
            foreach ($key in @('backend', 'frontend')) {
                $entry = $state.$key
                if (-not $entry -or -not $entry.processId) { continue }
                # PID 会被系统回收复用：只认「还是这些进程名」的，否则宁可不杀
                $name = Get-ProcessLabel -ProcessId ([int]$entry.processId)
                if ($name -notmatch '^(java|javaw|cmd|node|pwsh|powershell)$') {
                    Write-Note ("状态文件里的 PID {0} 已不是本服务的进程（{1}），忽略" -f $entry.processId, $name)
                    continue
                }
                $candidates.Add([pscustomobject]@{
                        Port      = $entry.port
                        ProcessId = [int]$entry.processId
                        Name      = $name
                        Source    = 'state'
                    })
            }
        }
        catch { Write-Warn "状态文件无法解析（忽略）：$stateFile" }
    }

    $pids = @($candidates | Select-Object -ExpandProperty ProcessId -Unique)
    if ($pids.Count -eq 0) {
        Write-Ok "没有检测到在运行的服务（:$BackendPort / :$FrontendPort 均空闲）"
        Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
        return 0
    }

    foreach ($pid0 in $pids) {
        $name = Get-ProcessLabel -ProcessId $pid0
        $ports = @($candidates | Where-Object { $_.ProcessId -eq $pid0 } | Select-Object -ExpandProperty Port -Unique)
        Write-Note ("停止 PID {0}（{1}，端口 {2}）" -f $pid0, $name, ($ports -join ' / '))
        Stop-ProcessTree -ProcessId $pid0
    }

    # 等端口真正释放（进程树退出有个尾巴）
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt 15) {
        if (-not (Test-PortListening -Port $BackendPort) -and -not (Test-PortListening -Port $FrontendPort)) { break }
        Start-Sleep -Milliseconds 300
    }
    $sw.Stop()

    $stuck = @()
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $owner = Get-PortOwnerPid -Port $port
        if ($null -ne $owner) {
            $stuck += ("{0}（PID {1}，{2}）" -f $port, $owner, (Get-ProcessLabel -ProcessId $owner))
        }
    }
    Remove-Item $stateFile -Force -ErrorAction SilentlyContinue

    if ($stuck.Count) {
        Write-Fail ("仍在监听：{0}。多为权限或进程被系统占用，手工用 taskkill /PID <pid> /T /F 处理。" -f ($stuck -join '、'))
        return 1
    }
    Write-Ok '已停止，端口已释放。'
    return 0
}

# ---------------------------------------------------------------- 主流程

if ($BackendOnly -and $FrontendOnly) {
    Write-Fail '不能同时指定 -BackendOnly 与 -FrontendOnly。'
    exit 2
}

if ($Stop) { exit (Invoke-StopAll) }

if (-not $onWindows) {
    Write-Fail '本脚本只支持 Windows（依赖 cmd.exe 与 taskkill 管进程树）；macOS / Linux 请按 README 手工启动。'
    exit 2
}

$state = [ordered]@{
    startedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    profile   = $Profile
    backend   = $null
    frontend  = $null
}
$failed = $false

# 上一次运行的记录：服务已在运行时据此保留 PID（否则重复执行会把状态文件写成空壳，
# -Stop 就只剩「按端口找占用者」一条路，包装进程的 cmd 会留下）
$previousState = $null
if (Test-Path $stateFile) {
    try { $previousState = Get-Content -Path $stateFile -Raw | ConvertFrom-Json } catch { }
}

if (-not $FrontendOnly) {
    Write-Step "后端 API（:$BackendPort，profile=$Profile）"
    if (Test-BackendReady) {
        Write-Ok '已在运行（探针返回 code=200），跳过启动'
        $state.backend = New-AlreadyRunningEntry -Previous $previousState.backend -Port $BackendPort
    }
    elseif (Test-PortListening -Port $BackendPort) {
        $owner = Get-PortOwnerPid -Port $BackendPort
        Write-Fail ("端口 {0} 被 PID {1}（{2}）占用，但它不响应本服务的探针。" -f $BackendPort, $owner, (Get-ProcessLabel -ProcessId $owner))
        Write-Note ("先确认是不是别的程序：taskkill /PID {0} /T /F，或 pwsh scripts/start.ps1 -Stop" -f $owner)
        $failed = $true
    }
    else {
        if ($Profile -eq 'dev') {
            if (-not (Test-PortListening -Port 3306)) {
                Write-Warn 'dev profile 需要 MySQL（localhost:3306）：端口未监听，启动大概率失败'
            }
            if (-not (Test-PortListening -Port 6379)) {
                Write-Note 'dev profile 的 Redis（localhost:6379）未监听：限流/验证码/吊销黑名单降级为进程内（单实例语义）'
            }
        }
        try {
            $svc = Start-BackendService
            Write-Note ("已后台启动 PID {0}：{1}" -f $svc.ProcessId, $svc.Command)
            Write-Note ("等待就绪，首次需编译，通常 40~90s（上限 {0}s）…" -f $TimeoutSeconds)
            $elapsed = Wait-UntilReady -Condition { Test-BackendReady } -Seconds $TimeoutSeconds -Label '等待后端'
            if ($elapsed -lt 0) {
                Write-Fail ("后端在 {0}s 内未就绪，已停掉本次拉起的进程（不留半启动状态）。" -f $TimeoutSeconds)
                Stop-ProcessTree -ProcessId $svc.ProcessId
                Show-LogTail -Path $svc.ErrLog -Lines 20 -Title 'stderr 末尾'
                Show-LogTail -Path $svc.Log -Lines 40 -Title 'stdout 末尾'
                $failed = $true
            }
            else {
                Write-Ok ("就绪（{0:N1}s）" -f $elapsed)
                $state.backend = [ordered]@{
                    processId   = $svc.ProcessId
                    port        = $BackendPort
                    log         = $svc.Log
                    errLog      = $svc.ErrLog
                    readySeconds = [math]::Round($elapsed, 1)
                }
            }
        }
        catch {
            Write-Fail ("后端启动失败：{0}" -f $_.Exception.Message)
            $failed = $true
        }
    }
}

if (-not $BackendOnly) {
    Write-Step "前端门户（:$FrontendPort）"
    if (Test-FrontendReady) {
        Write-Ok '已在运行（:3000 返回 index.html），跳过启动'
        $state.frontend = New-AlreadyRunningEntry -Previous $previousState.frontend -Port $FrontendPort
    }
    elseif (Test-PortListening -Port $FrontendPort) {
        $owner = Get-PortOwnerPid -Port $FrontendPort
        Write-Fail ("端口 {0} 被 PID {1}（{2}）占用，但它不是本前端。" -f $FrontendPort, $owner, (Get-ProcessLabel -ProcessId $owner))
        Write-Note ("先确认是不是别的程序：taskkill /PID {0} /T /F，或 pwsh scripts/start.ps1 -Stop" -f $owner)
        $failed = $true
    }
    else {
        try {
            Assert-FrontendDependencies
            $svc = Start-FrontendService
            Write-Note ("已后台启动 PID {0}：{1}" -f $svc.ProcessId, $svc.Command)
            $elapsed = Wait-UntilReady -Condition { Test-FrontendReady } -Seconds $TimeoutSeconds -Label '等待前端'
            if ($elapsed -lt 0) {
                Write-Fail ("前端在 {0}s 内未就绪，已停掉本次拉起的进程（不留半启动状态）。" -f $TimeoutSeconds)
                Stop-ProcessTree -ProcessId $svc.ProcessId
                Show-LogTail -Path $svc.ErrLog -Lines 20 -Title 'stderr 末尾'
                Show-LogTail -Path $svc.Log -Lines 40 -Title 'stdout 末尾'
                $failed = $true
            }
            else {
                Write-Ok ("就绪（{0:N1}s）" -f $elapsed)
                $state.frontend = [ordered]@{
                    processId = $svc.ProcessId
                    port      = $FrontendPort
                    log       = $svc.Log
                    errLog    = $svc.ErrLog
                    readySeconds = [math]::Round($elapsed, 1)
                }
            }
        }
        catch {
            Write-Fail ("前端启动失败：{0}" -f $_.Exception.Message)
            $failed = $true
        }
    }
}

($state | ConvertTo-Json -Depth 5) | Set-Content -Path $stateFile -Encoding utf8

$backendUp = Test-BackendReady
$frontendUp = Test-FrontendReady
$backendAttempted = -not $FrontendOnly
$frontendAttempted = -not $BackendOnly

Write-Step '就绪'
if ($backendUp) {
    Write-Host ("    后端 API   http://localhost:{0}" -f $BackendPort) -ForegroundColor Green
    Write-Host ("    接口文档   http://localhost:{0}/doc.html" -f $BackendPort) -ForegroundColor Green
}
elseif ($backendAttempted) {
    Write-Host ("    后端 API   :{0} 未就绪" -f $BackendPort) -ForegroundColor Yellow
}
if ($frontendUp) {
    Write-Host ("    前端门户   http://localhost:{0}" -f $FrontendPort) -ForegroundColor Green
}
elseif ($frontendAttempted) {
    Write-Host ("    前端门户   :{0} 未就绪" -f $FrontendPort) -ForegroundColor Yellow
}

$profileNote = if ($Profile -eq 'local') {
    '（内存 H2 + data-demo.sql 演示数据）'
}
else {
    '（MySQL :3306；Redis 建议一并启动）'
}
$accounts = if ($Profile -eq 'local') {
    'admin / worker01 / student01，密码 password'
}
else {
    'admin / logistics01 / worker01 / 2023010101，密码 password'
}
Write-Host ("    profile    {0}{1}" -f $Profile, $profileNote) -ForegroundColor DarkGray
Write-Host ("    内置账号   {0}" -f $accounts) -ForegroundColor DarkGray
Write-Host ("    日志       {0}" -f $logDir) -ForegroundColor DarkGray
Write-Host '    停止       pwsh scripts/start.ps1 -Stop' -ForegroundColor DarkGray

if ($failed) {
    Write-Host ''
    Write-Host '有服务未就绪，按上面的提示处理后重跑。' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '启动完成。' -ForegroundColor Green
exit 0
