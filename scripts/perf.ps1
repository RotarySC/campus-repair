#!/usr/bin/env pwsh
<#
.SYNOPSIS
    5 万单量级查询性能基线：只跑 @Tag("perf") 的压测类，打印 p50/p95。

.DESCRIPTION
    默认回归（mvn test）用 surefire 的 excludedGroups 把 perf 组排除在外，所以压测必须显式
    -Dgroups=perf -DexcludedGroups= 才会跑。本脚本封装这条命令并只回显 [perf] 指标行。

    产出的是**相对基线**（同一台机器、同一个 H2 内存库），不是 SLA 承诺：
    换机器、换 JDK、换数据量都会变，论文里应写成「本次实测 p95 = xx ms，用于观察趋势」。

.PARAMETER P95
    覆盖阈值毫秒数（默认 400，写死在测试里）。慢机器上只做观察时配合 -NoAssert。

.PARAMETER NoAssert
    只打印不断言（等价 -Dperf.assert=false），用于本机快速取数。

.EXAMPLE
    pwsh scripts/perf.ps1

.EXAMPLE
    pwsh scripts/perf.ps1 -P95 800 -NoAssert
#>
[CmdletBinding()]
param(
    [int]$P95 = 0,
    [switch]$NoAssert
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root '.scratch/logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'perf.log'

$env:JAVA_HOME = Join-Path $root '.tools/jdk/jdk-17.0.2'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $root '.tools/maven/apache-maven-3.9.16/bin') + ';' + $env:PATH

# 造 5 万单 + 2 万评价要往工作区内写临时文件（受限环境下系统 TEMP 会 Access Denied）
$tmp = Join-Path $root 'server/target/tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp

$mvnArgs = @(
    '-o', 'test',
    '-f', (Join-Path $root 'server/pom.xml'),
    '-Dgroups=perf',
    '-DexcludedGroups=',
    '-Dtest=AnalyticsPerformanceTest'
)
if ($P95 -gt 0) { $mvnArgs += "-Dperf.p95.ms=$P95" }
if ($NoAssert) { $mvnArgs += '-Dperf.assert=false' }

Write-Host '==> 压测：AnalyticsPerformanceTest（50k 工单 / 20k 评价）' -ForegroundColor Cyan
Push-Location (Join-Path $root 'server')
try {
    & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $log | Out-Null
    $code = $LASTEXITCODE
}
finally {
    Pop-Location
}

$perfLines = Get-Content -Path $log -ErrorAction SilentlyContinue | Select-String -Pattern '^\[perf\]'
if ($perfLines) {
    Write-Host ''
    $perfLines | ForEach-Object { Write-Host $_.Line -ForegroundColor Green }
    Write-Host ''
}
else {
    Write-Host '没有抓到 [perf] 指标行——压测类没跑起来，看日志末尾：' -ForegroundColor Red
    Get-Content -Path $log -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Write-Host "  $_" }
}
Write-Host "完整日志：$log"

if ($code -ne 0) {
    Write-Host "压测未通过（退出码 $code）：p95 超过阈值时用 -P95 <ms> 放宽，或用 -NoAssert 只取数。" -ForegroundColor Red
    exit $code
}
exit 0
