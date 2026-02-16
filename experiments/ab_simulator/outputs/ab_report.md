# A/B Test Report (Simulated Streaming Model)

## Setup

- Model backend: simulated streaming responses (short/medium/long)

- Workload: mixed + burst traffic for 180s

- A: fixed concurrency controller

- B: AIMD congestion controller


## Summary Metrics

| Metric | Fixed(A) | AIMD(B) | Delta(B-A) |
|---|---:|---:|---:|
| Actual RPM | 5422.09 | 4472.80 | -949.29 |
| Actual TPM | 4370265.00 | 3650501.93 | -719763.08 |
| RPM Utilization | 66.94% | 55.22% | -11.72pp |
| TPM Utilization | 100.00% | 96.19% | -3.81pp |
| Concurrency Utilization | 61.62% | 38.38% | -23.23pp |
| Composite Utilization | 61.62% | 38.38% | -23.23pp |
| P95 Latency (ms) | 1914.13 | 1977.49 | +63.36 |
| P99 Latency (ms) | 2197.76 | 2216.78 | +19.02 |
| Avg Queue Wait (ms) | 776.25 | 1013.21 | +236.96 |
| Sim GC Avg Freq (/s) | 1.19 | 0.99 | -0.20 |
| Sim GC Peak Freq (/s) | 2.00 | 2.00 | +0.00 |
| Python GC Collections | 1.00 | 1.00 | +0.00 |

## Rejection Breakdown

| Type | Fixed(A) | AIMD(B) |
|---|---:|---:|
| burst_reject | 0 | 0 |
| rate_rpm_reject | 0 | 0 |
| rate_tpm_reject | 0 | 0 |
| timeout_reject | 9604 | 12768 |

## Output Files

- `outputs/ab_report.md`
- `outputs/ab_timeseries.csv`
- `outputs/ab_gc_frequency.svg`
- `outputs/ab_throughput.svg`