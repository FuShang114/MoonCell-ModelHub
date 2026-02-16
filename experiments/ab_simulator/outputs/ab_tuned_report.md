# Tuned A/B Report (Fixed vs Best AIMD)

## Tuning Setup

- Search strategy: random search
- Trials: 80
- Workload: 180s mixed + burst simulated streaming
- Objective: throughput/composite-util up, GC and latency not degraded

## Best Parameters

- `min_c`: 8
- `max_c`: 96
- `init_c`: 14
- `ssthresh`: 24
- `decrease_factor`: 0.75
- `cooldown_windows`: 1
- `rate_limit_threshold`: 0.05
- `burst_threshold`: 0.05
- `p95_threshold_ms`: 2200
- `gc_threshold`: 2.0
- `slow_start_factor`: 1.8
- `ai_step`: 1.0

## Metrics Comparison

| Metric | Fixed | Tuned AIMD | Delta |
|---|---:|---:|---:|
| Actual RPM | 5422.09 | 4711.62 | -710.46 |
| Actual TPM | 4370265.00 | 3844322.31 | -525942.69 |
| RPM Utilization | 66.94% | 58.17% | -8.77pp |
| TPM Utilization | 100.00% | 100.00% | +0.00pp |
| Composite Utilization | 61.62% | 57.58% | -4.04pp |
| P95 Latency (ms) | 1914.13 | 1917.48 | +3.35 |
| P99 Latency (ms) | 2197.76 | 2203.22 | +5.47 |
| Avg Queue Wait (ms) | 776.25 | 799.73 | +23.47 |
| Sim GC Avg Freq (/s) | 1.19 | 1.05 | -0.14 |
| Sim GC Peak Freq (/s) | 2.00 | 2.00 | +0.00 |
| Objective Score | 1.00 | 0.81 | -0.19 |

## Output Files

- `outputs/aimd_tuning_results.csv`
- `outputs/aimd_best_params.json`
- `outputs/ab_tuned_timeseries.csv`
- `outputs/ab_tuned_gc_frequency.svg`
- `outputs/ab_tuned_throughput.svg`
- `outputs/ab_tuned_report.md`