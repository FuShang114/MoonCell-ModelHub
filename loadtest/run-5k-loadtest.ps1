# 5k 吞吐量压测脚本
# 目标: 180.184.67.230:9061
# 要求: 达到 5000 QPS 吞吐量

param(
    [string]$TargetIp = "180.184.67.230",
    [int]$Port = 9061,
    [string]$RpsLevels = "2000",
    [int]$DurationSec = 180
)

$ErrorActionPreference = "Stop"

$BaseUrl = "http://${TargetIp}:${Port}"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  2000 RPS 吞吐量压测" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "目标地址: $BaseUrl" -ForegroundColor Yellow
Write-Host "RPS 档位: $RpsLevels" -ForegroundColor Yellow
Write-Host "每档时长: ${DurationSec} 秒" -ForegroundColor Yellow
Write-Host ""

# 检查服务连接
Write-Host "检查服务连接..." -ForegroundColor Cyan
try {
    $response = Invoke-WebRequest -Uri "$BaseUrl/admin/monitor-metrics" -Method GET -TimeoutSec 5 -ErrorAction Stop
    Write-Host "✓ 服务可访问 (状态码: $($response.StatusCode))" -ForegroundColor Green
} catch {
    Write-Host "✗ 服务连接失败: $_" -ForegroundColor Red
    Write-Host "请确认服务地址和端口是否正确，或服务是否已启动" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "开始压测..." -ForegroundColor Green
Write-Host ""

# 设置环境变量并运行压测
$env:BASE_URL = $BaseUrl
$env:RPS_LEVELS = $RpsLevels
$env:DURATION_SEC = $DurationSec.ToString()

# 运行 Node.js 压测脚本
node .\loadtest\run-compare-node.mjs

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  压测完成" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "结果文件位置: loadtest\results\compare.csv" -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "压测过程中出现错误 (退出码: $LASTEXITCODE)" -ForegroundColor Red
    exit $LASTEXITCODE
}
