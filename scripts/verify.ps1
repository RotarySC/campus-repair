#!/usr/bin/env pwsh
<#
.SYNOPSIS
    一键校验：后端单元/集成测试 + 两个前端包的类型检查与单测。

.DESCRIPTION
    仓库里能进 CI 的四段检查按固定顺序跑完，任一失败即非 0 退出，并在末尾打印每段耗时与用例数：

      1. server            mvn test（单元 + 集成；perf 组默认被 surefire 排除）
      2. packages/shared   tsc --noEmit + vitest run
      3. apps/admin-web    vue-tsc --noEmit + vitest run

    真机探针（.scratch/assert-*.mjs）依赖本机 Edge 与两个服务，不在本脚本内——它不可无头复跑，
    属于人工验收而不是 CI 校验。

.PARAMETER SkipBackend
    跳过第 1 段（只改前端时用）。

.PARAMETER SkipShared
    跳过第 2 段。

.PARAMETER SkipAdmin
    跳过第 3 段。

.EXAMPLE
    pwsh scripts/verify.ps1

.EXAMPLE
    pwsh scripts/verify.ps1 -SkipBackend
#>
[CmdletBinding()]
param(
    [switch]$SkipBackend,
    [switch]$SkipShared,
    [switch]$SkipAdmin
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root '.scratch/logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$results = [System.Collections.Generic.List[object]]::new()
$failed = $false

function Invoke-Segment {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Action,
        [string]$WorkDir = $root
    )

    Write-Host ''
    Write-Host "==> $Name" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $log = Join-Path $logDir ("{0}.log" -f ($Name -replace '[^A-Za-z0-9]+', '-'))
    Push-Location $WorkDir
    try {
        # 2>&1 让 mvn/vitest 的 stderr 也进日志；不抛异常，靠退出码判定
        & $Action 2>&1 | Tee-Object -FilePath $log | Out-Null
        $code = $LASTEXITCODE
    }
    catch {
        $_ | Out-String | Add-Content -Path $log
        $code = 1
    }
    finally {
        Pop-Location
        $sw.Stop()
    }

    $script:failed = $script:failed -or ($code -ne 0)

    # 从日志里抠出用例数：mvn 的汇总行 / vitest 的 Tests 行
    $count = '?'
    $tail = Get-Content -Path $log -ErrorAction SilentlyContinue
    $mvnSummary = $tail | Select-String -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' | Select-Object -Last 1
    $vitestSummary = $tail | Select-String -Pattern '^\s*Tests\s+\d+ passed' | Select-Object -Last 1
    if ($mvnSummary) {
        $m = [regex]::Match($mvnSummary.Line, 'Tests run: (\d+)')
        if ($m.Success) { $count = $m.Groups[1].Value }
    }
    elseif ($vitestSummary) {
        $m = [regex]::Match($vitestSummary.Line, 'Tests\s+(\d+) passed')
        if ($m.Success) { $count = $m.Groups[1].Value }
    }

    $results.Add([pscustomobject]@{ Segment = $Name; Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1); Cases = $count; Exit = $code })
    if ($code -eq 0) {
        Write-Host ("    OK  ({0}s, {1} 例)" -f [math]::Round($sw.Elapsed.TotalSeconds, 1), $count) -ForegroundColor Green
    }
    else {
        Write-Host ("    FAILED  ({0}s) —— 末尾 25 行：" -f [math]::Round($sw.Elapsed.TotalSeconds, 1)) -ForegroundColor Red
        $tail | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" }
        Write-Host "    完整日志：$log" -ForegroundColor Yellow
    }
}

Push-Location $root
try {
    $env:JAVA_HOME = Join-Path $root '.tools/jdk/jdk-17.0.2'
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $root '.tools/maven/apache-maven-3.9.16/bin') + ';' + $env:PATH

    # mvn 在受限环境里会让 ImageIO 相关用例全红：把 java.io.tmpdir 指到工作区内
    $tmp = Join-Path $root 'server/target/tmp'
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $env:TEMP = $tmp
    $env:TMP = $tmp

    if (-not $SkipBackend) {
        Invoke-Segment -Name 'server: mvn test' -WorkDir (Join-Path $root 'server') -Action {
            & mvn test -f (Join-Path $root 'server/pom.xml')
        }
    }

    if (-not $SkipShared) {
        $shared = Join-Path $root 'packages/shared'
        Invoke-Segment -Name 'shared: tsc + vitest' -WorkDir $shared -Action {
            & (Join-Path $shared 'node_modules/.bin/tsc.CMD') --noEmit -p tsconfig.json
            if ($LASTEXITCODE -ne 0) { return }
            & node (Join-Path $shared 'node_modules/vitest/vitest.mjs') run
        }
    }

    if (-not $SkipAdmin) {
        $admin = Join-Path $root 'apps/admin-web'
        Invoke-Segment -Name 'admin-web: vue-tsc + vitest' -WorkDir $admin -Action {
            & (Join-Path $admin 'node_modules/.bin/vue-tsc.CMD') --noEmit
            if ($LASTEXITCODE -ne 0) { return }
            & node (Join-Path $admin 'node_modules/vitest/vitest.mjs') run
        }
    }
}
finally {
    Pop-Location
}

Write-Host ''
Write-Host '== 汇总 ==' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host

if ($failed) {
    Write-Host '校验未通过：上面有 FAILED 段，日志在 .scratch/logs/。' -ForegroundColor Red
    exit 1
}
Write-Host '全部通过。' -ForegroundColor Green
exit 0
