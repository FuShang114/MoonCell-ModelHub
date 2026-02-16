# Multi-Scenario A/B Test (Full Dimensions)

对比对象：Fixed 并发控制 vs Tuned AIMD。

## steady_balanced
- 结论：**AIMD中性/需进一步调优**（score=0.953）
- 吞吐RPM：Fixed=4019.49, AIMD=4019.49
- 复合利用率：Fixed=45.11%, AIMD=45.11%
- TTFT p95(ms)：Fixed=212.66, AIMD=221.18
- E2E p95(ms)：Fixed=900.55, AIMD=906.16
- Reject率：Fixed=0.00%, AIMD=0.00%
- GC均值(/s)：Fixed=0.89, AIMD=0.89

## steady_high_pressure
- 结论：**AIMD不适合当前场景**（score=0.818）
- 吞吐RPM：Fixed=5858.38, AIMD=4397.91
- 复合利用率：Fixed=58.59%, AIMD=38.38%
- TTFT p95(ms)：Fixed=1340.89, AIMD=1377.17
- E2E p95(ms)：Fixed=1987.59, AIMD=2053.56
- Reject率：Fixed=18.02%, AIMD=38.46%
- GC均值(/s)：Fixed=1.24, AIMD=0.96

## bursty_traffic
- 结论：**AIMD不适合当前场景**（score=0.935）
- 吞吐RPM：Fixed=5230.26, AIMD=4731.60
- 复合利用率：Fixed=64.57%, AIMD=56.57%
- TTFT p95(ms)：Fixed=1338.01, AIMD=1342.25
- E2E p95(ms)：Fixed=1839.90, AIMD=1861.36
- Reject率：Fixed=36.32%, AIMD=42.39%
- GC均值(/s)：Fixed=1.17, AIMD=1.07

## long_context_heavy
- 结论：**AIMD不适合当前场景**（score=0.660）
- 吞吐RPM：Fixed=1116.03, AIMD=439.81
- 复合利用率：Fixed=13.78%, AIMD=5.43%
- TTFT p95(ms)：Fixed=1413.26, AIMD=1498.46
- E2E p95(ms)：Fixed=2183.67, AIMD=2816.62
- Reject率：Fixed=77.06%, AIMD=90.96%
- GC均值(/s)：Fixed=0.78, AIMD=0.43

## short_chat_realtime
- 结论：**AIMD不适合当前场景**（score=0.877）
- 吞吐RPM：Fixed=3734.29, AIMD=3271.97
- 复合利用率：Fixed=27.06%, AIMD=18.18%
- TTFT p95(ms)：Fixed=1270.01, AIMD=1272.54
- E2E p95(ms)：Fixed=1439.89, AIMD=1447.66
- Reject率：Fixed=51.66%, AIMD=57.64%
- GC均值(/s)：Fixed=0.28, AIMD=0.25

## token_quota_tight
- 结论：**AIMD不适合当前场景**（score=0.695）
- 吞吐RPM：Fixed=1456.60, AIMD=585.64
- 复合利用率：Fixed=17.98%, AIMD=7.23%
- TTFT p95(ms)：Fixed=1389.27, AIMD=1443.38
- E2E p95(ms)：Fixed=2056.13, AIMD=2320.29
- Reject率：Fixed=77.87%, AIMD=91.10%
- GC均值(/s)：Fixed=0.81, AIMD=0.41

## Overall Summary
- AIMD优秀场景：无
- AIMD中性场景：steady_balanced
- AIMD不适合场景：steady_high_pressure, bursty_traffic, long_context_heavy, short_chat_realtime, token_quota_tight
- 输出明细：`outputs/scenario_matrix_results.csv`