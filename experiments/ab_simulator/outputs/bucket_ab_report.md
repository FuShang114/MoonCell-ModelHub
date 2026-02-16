# Static vs Adaptive Bucket A/B

## mixed_bursty
- RPM: Static=4011.18, Adaptive=4388.90
- max(rpm_util,tpm_util): Static=56.00%, Adaptive=67.64%
- TTFT p95(ms): Static=1305.17, Adaptive=1312.65
- E2E p95(ms): Static=1638.92, Adaptive=1684.16
- Reject rate: Static=51.67%, Adaptive=47.12%
- GC avg(/s): Static=0.56, Adaptive=0.68
- Adaptive boundaries(end): [329, 1020, 2499]
- Adaptive shares(end): [0.2573, 0.2476, 0.2476, 0.2476]

## token_heavy
- RPM: Static=341.40, Adaptive=999.31
- max(rpm_util,tpm_util): Static=20.64%, Adaptive=83.80%
- TTFT p95(ms): Static=1358.46, Adaptive=1431.46
- E2E p95(ms): Static=1888.70, Adaptive=2253.92
- Reject rate: Static=95.93%, Adaptive=88.09%
- GC avg(/s): Static=0.13, Adaptive=0.57
- Adaptive boundaries(end): [1913, 2609, 2979]
- Adaptive shares(end): [0.25, 0.25, 0.25, 0.25]
