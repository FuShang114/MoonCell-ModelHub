# Full Pipeline vs Traditional LB

对比：完整负载均衡链路 vs 传统随机采样+最小并发。

## balanced_steady
- RPM: Traditional=4230.96, Full=3262.25
- max(rpm_util,tpm_util): Traditional=82.63%, Full=53.25%
- TTFT p95(ms): Traditional=211.95, Full=1328.18
- E2E p95(ms): Traditional=901.21, Full=1947.70
- Reject rate: Traditional=0.00%, Full=22.90%
- GC avg(/s): Traditional=0.93, Full=0.59
- Full final alpha: 1.000
- Full boundaries: [821, 1293, 2841]
- Full shares: [0.384, 0.2296, 0.1932, 0.1932]

## mixed_bursty
- RPM: Traditional=5231.78, Full=3213.42
- max(rpm_util,tpm_util): Traditional=100.00%, Full=44.68%
- TTFT p95(ms): Traditional=1338.51, Full=1319.64
- E2E p95(ms): Traditional=1853.15, Full=1765.05
- Reject rate: Traditional=36.27%, Full=60.86%
- GC avg(/s): Traditional=1.17, Full=0.45
- Full final alpha: 0.710
- Full boundaries: [424, 1137, 2732]
- Full shares: [0.4235, 0.1922, 0.1922, 0.1922]

## token_heavy
- RPM: Traditional=1452.38, Full=582.63
- max(rpm_util,tpm_util): Traditional=100.00%, Full=51.68%
- TTFT p95(ms): Traditional=1387.56, Full=1442.04
- E2E p95(ms): Traditional=2058.24, Full=2325.48
- Reject rate: Traditional=78.04%, Full=91.19%
- GC avg(/s): Traditional=0.80, Full=0.35
- Full final alpha: 0.710
- Full boundaries: [2264, 3017, 3446]
- Full shares: [0.3521, 0.2346, 0.2067, 0.2067]

## long_context
- RPM: Traditional=1206.93, Full=476.41
- max(rpm_util,tpm_util): Traditional=100.00%, Full=50.93%
- TTFT p95(ms): Traditional=1400.85, Full=1491.57
- E2E p95(ms): Traditional=2112.25, Full=2753.58
- Reject rate: Traditional=75.26%, Full=90.23%
- GC avg(/s): Traditional=0.78, Full=0.33
- Full final alpha: 0.710
- Full boundaries: [2827, 4588, 5377]
- Full shares: [0.358, 0.2343, 0.2038, 0.2038]
