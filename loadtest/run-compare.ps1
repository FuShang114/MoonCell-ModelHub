param(
  [Parameter(Mandatory = $true)]
  [string]$BaseUrl,
  [int[]]$RpsLevels = @(3, 6, 9),
  [int]$DurationSeconds = 180,
  [string]$OutDir = "loadtest\results",
    # Use an ASCII-only default prompt to avoid any potential parsing issues on older PowerShell versions.
    [string]$Prompt = "Summarize the following text in one concise sentence."
)

$ErrorActionPreference = "Stop"

function Require-Command {
  param([string]$Name)
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "缺少命令: $Name。请先安装后重试。"
  }
}

function Set-Algorithm {
  param([string]$Algo)
  $body = @{ algorithm = $Algo } | ConvertTo-Json
  # Avoid using "$BaseUrl/..." inline (which can be mis-parsed on some PowerShell/encoding combos as $BaseUrl / admin).
  # Build the URI via string concatenation instead to keep it unambiguous.
  $uri = $BaseUrl + "/admin/load-balancing/settings"
  Invoke-RestMethod -Method Put -Uri $uri -ContentType "application/json" -Body $body | Out-Null
}

function Start-MonitorSampler {
  param(
    [string]$BaseUrl,
    [string]$FilePath
  )

  $job = Start-Job -ScriptBlock {
    param($u, $f)
    while ($true) {
      try {
        $m = Invoke-RestMethod -Method Get -Uri "$u/admin/monitor-metrics"
        $s = Invoke-RestMethod -Method Get -Uri "$u/admin/load-balancing/strategy-statuses"
        $line = @{
          ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
          monitor = $m
          strategy = $s
        } | ConvertTo-Json -Depth 8 -Compress
        Add-Content -Path $f -Value $line
      } catch {
      }
      Start-Sleep -Seconds 5
    }
  } -ArgumentList $BaseUrl, $FilePath

  return $job
}

function Stop-MonitorSampler {
  param($Job)
  if ($null -ne $Job) {
    Stop-Job $Job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $Job -ErrorAction SilentlyContinue | Out-Null
  }
}

function Get-Average {
  param([double[]]$Values)
  if ($null -eq $Values -or $Values.Count -eq 0) { return 0.0 }
  return ($Values | Measure-Object -Average).Average
}

function Summarize-Case {
  param(
    [string]$SummaryPath,
    [string]$SamplePath,
    [string]$Algo,
    [int]$Rps,
    [int]$DurationSeconds,
    [string]$CsvPath
  )

  $summary = Get-Content $SummaryPath -Raw | ConvertFrom-Json
  $durationP95 = [double]$summary.metrics.http_req_duration.values.'p(95)'
  $errorRate = [double]$summary.metrics.http_req_failed.values.rate
  $realRps = [double]$summary.metrics.http_reqs.values.rate

  $cpu = @()
  $gc = @()
  $qps = @()
  $succ = @()
  $fail = @()
  $throughput = @()
  $resource = @()
  $queueReject = 0L
  $budgetReject = 0L
  $samplingReject = 0L

  if (Test-Path $SamplePath) {
    Get-Content $SamplePath | ForEach-Object {
      if ([string]::IsNullOrWhiteSpace($_)) { return }
      $obj = $_ | ConvertFrom-Json
      $m = $obj.monitor
      if ($null -ne $m) {
        $cpu += [double]($(if ($null -ne $m.cpuUsage) { $m.cpuUsage } else { 0 }))
        $gc += [double]($(if ($null -ne $m.gcRatePerMin) { $m.gcRatePerMin } else { 0 }))
        $qps += [double]($(if ($null -ne $m.qps) { $m.qps } else { 0 }))
        $succ += [double]($(if ($null -ne $m.successRate) { $m.successRate } else { 0 }))
        $fail += [double]($(if ($null -ne $m.failureRate) { $m.failureRate } else { 0 }))
        $throughput += [double]($(if ($null -ne $m.throughput) { $m.throughput } else { 0 }))
        $resource += [double]($(if ($null -ne $m.resourceUsage) { $m.resourceUsage } else { 0 }))
      }
      if ($null -ne $obj.strategy -and $obj.strategy.Count -gt 0) {
        $active = $obj.strategy | Where-Object { $_.state -eq "ACTIVE" } | Select-Object -First 1
        if ($null -ne $active) {
          if ($null -ne $active.rejectQueueFull) { $queueReject = [long]$active.rejectQueueFull }
          if ($null -ne $active.rejectBudget) { $budgetReject = [long]$active.rejectBudget }
          if ($null -ne $active.rejectSampling) { $samplingReject = [long]$active.rejectSampling }
        }
      }
    }
  }

  $line = "{0},{1},{2},{3:N2},{4:P2},{5:N2},{6:N2},{7:N2},{8:P2},{9:P2},{10:N2},{11:P2},{12},{13},{14}" -f `
    $Algo, $Rps, $DurationSeconds, `
    $realRps, $errorRate, $durationP95, `
    (Get-Average $gc), (Get-Average $cpu), (Get-Average $succ), (Get-Average $fail), `
    (Get-Average $throughput), (Get-Average $resource), `
    $queueReject, $budgetReject, $samplingReject

  Add-Content -Path $CsvPath -Value $line
}

Require-Command -Name "k6"
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$csv = Join-Path $OutDir "compare.csv"
"algo,target_rps,duration_sec,actual_rps,error_rate,latency_p95_ms,gc_per_min_avg,cpu_usage_avg,qps_avg,success_rate_avg,failure_rate_avg,throughput_avg,resource_usage_avg,reject_queue_full,reject_budget,reject_sampling" | Set-Content -Path $csv

$algorithms = @("TRADITIONAL", "OBJECT_POOL")
foreach ($algo in $algorithms) {
  Write-Host "==== 切换算法: $algo ====" -ForegroundColor Cyan
  Set-Algorithm -Algo $algo
  Start-Sleep -Seconds 5

  foreach ($rps in $RpsLevels) {
    $caseName = "$algo-rps$rps-d${DurationSeconds}s"
    $summaryPath = Join-Path $OutDir "$caseName-summary.json"
    $samplePath = Join-Path $OutDir "$caseName-samples.jsonl"
    if (Test-Path $samplePath) { Remove-Item $samplePath -Force }

    Write-Host "---- 执行: $caseName ----" -ForegroundColor Yellow
    $samplerJob = Start-MonitorSampler -BaseUrl $BaseUrl -FilePath $samplePath
    try {
      k6 run ".\loadtest\k6-chat.js" `
        --summary-export $summaryPath `
        -e BASE_URL=$BaseUrl `
        -e RPS=$rps `
        -e DURATION="${DurationSeconds}s" `
        -e PROMPT="$Prompt"
    } finally {
      Stop-MonitorSampler -Job $samplerJob
    }

    Summarize-Case `
      -SummaryPath $summaryPath `
      -SamplePath $samplePath `
      -Algo $algo `
      -Rps $rps `
      -DurationSeconds $DurationSeconds `
      -CsvPath $csv
  }
}

Write-Host "压测完成。结果文件: $csv" -ForegroundColor Green
