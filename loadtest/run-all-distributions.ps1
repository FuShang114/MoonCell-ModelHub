# 运行三种数据分布模式的压测：同构、异构、混合
# Usage: .\loadtest\run-all-distributions.ps1 -BaseUrl "http://127.0.0.1:9061" -RpsLevels @(4,8,12) -DurationSec 300

param(
    [string]$BaseUrl = "http://127.0.0.1:9061",
    [int[]]$RpsLevels = @(4, 8, 12),
    [int]$DurationSec = 300,
    [double]$MixedWeight = 0.5,
    [string]$BatchName = ""
)

$ErrorActionPreference = "Stop"

Write-Host "=== 数据分布压测：同构、异构、混合 ===" -ForegroundColor Cyan
Write-Host "BaseUrl: $BaseUrl" -ForegroundColor Yellow
Write-Host "RPS Levels: $($RpsLevels -join ',')" -ForegroundColor Yellow
Write-Host "Duration: ${DurationSec}s per case" -ForegroundColor Yellow
Write-Host "Mixed Weight: $MixedWeight" -ForegroundColor Yellow
Write-Host ""

$rpsStr = $RpsLevels -join ","

# 1. 同构数据（homogeneous）
Write-Host "`n[1/3] 运行同构数据压测..." -ForegroundColor Green
$env:BASE_URL = $BaseUrl
$env:RPS_LEVELS = $rpsStr
$env:DURATION_SEC = $DurationSec
$env:DATA_DISTRIBUTION = "homogeneous"
if ($BatchName) {
    $env:BATCH_NAME = "${BatchName}-homogeneous"
} else {
    $env:BATCH_NAME = ""
}
node .\loadtest\run-compare-data-distribution.mjs
if ($LASTEXITCODE -ne 0) {
    Write-Host "同构数据压测失败" -ForegroundColor Red
    exit 1
}

# 2. 异构数据（heterogeneous）
Write-Host "`n[2/3] 运行异构数据压测..." -ForegroundColor Green
$env:DATA_DISTRIBUTION = "heterogeneous"
if ($BatchName) {
    $env:BATCH_NAME = "${BatchName}-heterogeneous"
} else {
    $env:BATCH_NAME = ""
}
node .\loadtest\run-compare-data-distribution.mjs
if ($LASTEXITCODE -ne 0) {
    Write-Host "异构数据压测失败" -ForegroundColor Red
    exit 1
}

# 3. 混合数据（mixed）
Write-Host "`n[3/3] 运行混合数据压测（随机权重）..." -ForegroundColor Green
$env:DATA_DISTRIBUTION = "mixed"
$env:MIXED_WEIGHT = $MixedWeight
if ($BatchName) {
    $env:BATCH_NAME = "${BatchName}-mixed"
} else {
    $env:BATCH_NAME = ""
}
node .\loadtest\run-compare-data-distribution.mjs
if ($LASTEXITCODE -ne 0) {
    Write-Host "混合数据压测失败" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 所有数据分布压测完成 ===" -ForegroundColor Cyan
Write-Host "结果文件在: loadtest\results\" -ForegroundColor Yellow
