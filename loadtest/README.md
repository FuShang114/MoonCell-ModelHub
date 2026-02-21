# 低 Token 压测与双方案对比

本目录用于对比 `TRADITIONAL` 与 `OBJECT_POOL` 两种负载均衡策略，强调：

- 多维度指标对比（延迟、错误率、QPS、GC、CPU、吞吐、资源使用、拒绝分布）
- 控制 token 用量（默认中等短文本、低 RPS、每档 3 分钟）

## 1. 前置条件

- 服务已启动并可访问（示例：`http://127.0.0.1:9061`）
- 已安装 `k6`
  - Windows: `choco install k6` 或 `winget install k6`
- PowerShell 可执行脚本

## 2. 压测脚本

- `k6-chat.js`：单场景恒定到达率压测
- `run-compare.ps1`：自动切换算法 + 分档压测 + 采样监控 + 输出对比 CSV
- `run-compare-node.mjs`：Node 版对比压测（无 k6 依赖）
- `run-compare-persist.mjs`：持续持久化版（推荐，随时中断也有数据）
- `run-compare-data-distribution.mjs`：**数据分布模式压测**（同构/异构/混合）
- `run-all-distributions.ps1`：一键运行三种数据分布模式

## 3. 一键运行（推荐）

```powershell
.\loadtest\run-compare.ps1 -BaseUrl "http://127.0.0.1:9061"
```

默认参数：

- 算法：`TRADITIONAL` -> `OBJECT_POOL`
- 压力档位：`3,6,9 rps`
- 每档时长：`180s`
- 请求内容：中等短文本（控制 token）

输出文件在 `loadtest/results/`：

- `*-summary.json`：每个用例的 k6 结果
- `*-samples.jsonl`：每 5 秒采样的监控与策略状态
- `compare.csv`：汇总对比结果

## 4. 自定义低成本参数

例如更保守地压测（token 更少）：

```powershell
.\loadtest\run-compare.ps1 `
  -BaseUrl "http://127.0.0.1:8080" `
  -RpsLevels @(2,4,6) `
  -DurationSeconds 120 `
  -Prompt "请用一句话总结这段话的含义。"
```

## 5. 建议对比维度（优缺点分析）

读取 `compare.csv` 时重点看：

- **稳定性**：`error_rate`、`reject_*`
- **时延**：`latency_p95_ms`
- **吞吐**：`actual_rps`、`throughput`
- **资源成本**：`cpu_usage_avg`、`gc_per_min_avg`、`resource_usage_avg`
- **成功质量**：`success_rate_avg` / `failure_rate_avg`

一般结论方向：

- `TRADITIONAL`：逻辑简单，低负载下延迟可控；高波动 token 场景下塑形能力偏弱。
- `OBJECT_POOL`：在混合请求与预算约束场景更易稳定资源曲线；但调参与状态观测复杂度更高。

## 6. 持久化压测（不中断丢数据）

```powershell
$env:BASE_URL="http://127.0.0.1:9061"
$env:RPS_LEVELS="4,8,12"
$env:DURATION_SEC="300"
node .\loadtest\run-compare-persist.mjs
```

每次运行会生成独立目录（按测试批次）：`loadtest/results/batch-001/`、`batch-002/`...，包含：

- `compare.csv`：已完成用例的汇总（每组结束即追加）
- `progress.jsonl`：运行进度流（开始/采样/结束/异常）
- `samples/*.jsonl`：每个用例的监控与策略状态采样

这样即使中途停止，也能基于已落盘数据继续分析。

可手动指定批次名：

```powershell
$env:BATCH_NAME="batch-prod-20260218"
node .\loadtest\run-compare-persist.mjs
```

## 7. 数据分布模式压测（新增）

支持三种数据分布模式，测试不同 token 分布对负载均衡策略的影响：

### 7.1 三种模式

- **同构数据（homogeneous）**：所有请求使用相似长度的 prompt（固定 medium 长度），token 分布集中
- **异构数据（heterogeneous）**：请求长度差异大（20% 短、60% 中、20% 长），token 分布分散
- **混合数据（mixed）**：按权重随机选择同构或异构模式，模拟真实混合场景

### 7.2 一键运行三种模式

```powershell
.\loadtest\run-all-distributions.ps1 `
  -BaseUrl "http://127.0.0.1:9061" `
  -RpsLevels @(4,8,12) `
  -DurationSec 300 `
  -MixedWeight 0.5 `
  -BatchName "dist-test-001"
```

参数说明：
- `-BaseUrl`：服务地址（默认 `http://127.0.0.1:9061`）
- `-RpsLevels`：RPS 档位数组（默认 `@(4,8,12)`）
- `-DurationSec`：每档压测时长（秒，默认 300）
- `-MixedWeight`：混合模式下同构的权重（0-1，默认 0.5）
- `-BatchName`：批次名称（可选，用于区分不同测试批次）

### 7.3 单独运行某种模式

```powershell
# 同构数据
$env:BASE_URL="http://127.0.0.1:9061"
$env:RPS_LEVELS="4,8,12"
$env:DURATION_SEC="300"
$env:DATA_DISTRIBUTION="homogeneous"
node .\loadtest\run-compare-data-distribution.mjs

# 异构数据
$env:DATA_DISTRIBUTION="heterogeneous"
node .\loadtest\run-compare-data-distribution.mjs

# 混合数据（50% 同构，50% 异构）
$env:DATA_DISTRIBUTION="mixed"
$env:MIXED_WEIGHT="0.5"
node .\loadtest\run-compare-data-distribution.mjs
```

### 7.4 输出说明

CSV 文件新增 `data_distribution` 列，标识数据分布模式：
- `homogeneous`：同构数据
- `heterogeneous`：异构数据
- `mixed`：混合数据

对比分析时，可重点关注：
- **同构 vs 异构**：token 分布集中 vs 分散对策略稳定性的影响
- **混合模式**：真实场景下的表现
- **不同策略**：`TRADITIONAL` 和 `OBJECT_POOL` 在不同数据分布下的优劣

