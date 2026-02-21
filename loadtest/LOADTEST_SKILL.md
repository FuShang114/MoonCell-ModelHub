## MoonCell Gateway 压测 Skill（面向 AI Agent）

**用途**：指导后续在本仓库内运行的 agent，如何使用 `loadtest` 目录下的 **Node 脚本** 对网关进行压测，对比 `TRADITIONAL` 与 `OBJECT_POOL` 等策略，并产出可分析的结果。

---

### 一、前置条件

- **服务已启动**
  - **要求**：Gateway 服务已在本机或指定环境启动，并暴露 HTTP 接口。
  - **典型地址**：`http://127.0.0.1:9061`
  - **检查方式**：agent 可通过 `GET {BASE_URL}/actuator/health` 或 `GET {BASE_URL}/admin/monitor-metrics` 探活（若用户未提供 BASE_URL，优先尝试 `http://127.0.0.1:9061`）。

- **Node 环境**
  - 建议：Node 18+（内置 `fetch`，支持 `AbortSignal.timeout`）。
  - 运行命令时统一使用：`node .\loadtest\xxx.mjs`（Windows PowerShell 环境）。

- **仓库路径**
  - 本说明假定工作目录为仓库根目录：`D:\MoonCell-ModelHub`（如不一致，agent 需先 `cd` 到根目录再执行）。

- **测试风险提示**
  - 所有脚本默认访问 **真实下游模型服务**，会消耗 token / 资源。
  - 若用户强调“低成本/预演”，优先选择较低 `RPS_LEVELS` 与较短 `DURATION_SEC`。

---

### 二、Node 压测脚本总览

- **`run-compare-node.mjs`**
  - 作用：纯 Node 压测，对比 `TRADITIONAL` 与 `OBJECT_POOL`，**不依赖 k6**。
  - 特点：单次运行输出一个 `compare.csv`，并在 `loadtest/results` 写入 `*-samples.jsonl` 采样。

- **`run-compare-persist.mjs`**
  - 作用：**持久化批次压测**，每个批次独立目录（如 `batch-001`）。
  - 特点：即使中途终止，也有 `progress.jsonl` 和 `samples/*.jsonl` 可供后续分析。

- **`run-compare-data-distribution.mjs`**
  - 作用：在不同 **数据分布模式** 下（同构 / 异构 / 混合）压测策略表现。
  - 输出：带 `data_distribution` 列的 `compare.csv` + 详细采样。

- **分析脚本**
  - `analyze-failure-reasons.mjs`：聚合某批次失败原因分布。
  - `analyze-failures-detailed.mjs`：按原因 / HTTP 状态码 / 用例维度输出详细失败分析。
  - `generate-report.mjs`：基于某次 `compare.csv` 生成可视化 HTML 报告。

---

### 三、标准环境变量（agent 应优先使用）

所有 Node 压测入口都依赖下列环境变量（默认为 README 中推荐值，agent 可根据“低成本 / 高强度”需求调整）：

- **`BASE_URL`**：网关地址，默认 `http://127.0.0.1:9061`
- **`RPS_LEVELS`**：逗号分隔的 RPS 档位，默认 `"4,8,12"` 或 README 中示例值
- **`DURATION_SEC`**：每档压测时长（秒），常用：`120` / `180` / `300`
- **`PROMPT`**（仅部分脚本使用）：
  - `run-compare-node.mjs`：可选固定 prompt；不设时使用内置短/中/长混合 prompt 池。
  - `run-compare-persist.mjs`：单一固定 prompt。

- **批次与分布相关**
  - `OUT_DIR`：结果根目录，默认为 `loadtest/results`
  - `BATCH_NAME`：手动指定批次名（否则自动生成 `batch-001`、`batch-002` ...）
  - `DATA_DISTRIBUTION`：`homogeneous` / `heterogeneous` / `mixed`
  - `MIXED_WEIGHT`：混合模式下“同构”的权重（0–1），默认 `0.5`

---

### 四、推荐压测入口（Node 优先）

#### 4.1 快速对比压测（**低成本预检**）

- **适用场景**：用户想快速看一下两个算法在较低负载下的大致差异。
- **命令（Windows PowerShell）**：

```powershell
$env:BASE_URL="http://127.0.0.1:9061"
$env:RPS_LEVELS="3,6,9"        # 可根据需求调低/调高
$env:DURATION_SEC="120"        # 每档 120s，低成本
node .\loadtest\run-compare-node.mjs
```

- **产物位置**：
  - `loadtest/results/compare.csv`
  - `loadtest/results/TRADITIONAL-rpsX-samples.jsonl`
  - `loadtest/results/OBJECT_POOL-rpsX-samples.jsonl`

- **agent 行动要点**：
  - 若用户未指定 RPS / 时长，默认使用 README 里的温和参数。
  - 压测结束后，提示用户可以用 `generate-report.mjs` 生成 HTML 报告（见 §6）。

---

#### 4.2 持久化批次压测（**推荐的标准流程**）

- **适用场景**：需要可中断、可追踪进度、可反复分析的一次完整压测。

```powershell
$env:BASE_URL="http://127.0.0.1:9061"
$env:RPS_LEVELS="4,8,12"
$env:DURATION_SEC="300"
$env:OUT_DIR="loadtest/results"
# 可选：手动命名批次，便于区分环境/日期
# $env:BATCH_NAME="batch-prod-20260218"
node .\loadtest\run-compare-persist.mjs
```

- **产物结构**（示例）：
  - `loadtest/results/batch-001/`
    - `compare.csv`：逐个用例完成后即时追加
    - `progress.jsonl`：包含 `run_start` / `case_start` / `sample` / `case_end` / `run_end` 等事件
    - `samples/*.jsonl`：每个 `(algo,rps)` 用例的监控与策略状态采样

- **agent 行动要点**：
  - 若用户希望“能看见实时进度”，可以 tail `progress.jsonl` 告诉用户当前跑到哪个用例。
  - 若用户中途要求停止压测，agent 应提醒用户：已有数据仍保存在对应 `batch-xxx` 目录中，可用分析脚本继续做诊断。

---

#### 4.3 数据分布模式压测（**同构 / 异构 / 混合**）

- **适用场景**：用户关心不同 **请求 token 分布** 下策略表现差异。
- **单一模式示例**：

```powershell
# 同构数据（所有请求长度相近，中等长度）
$env:BASE_URL="http://127.0.0.1:9061"
$env:RPS_LEVELS="4,8,12"
$env:DURATION_SEC="300"
$env:DATA_DISTRIBUTION="homogeneous"
node .\loadtest\run-compare-data-distribution.mjs
```

- **切换为异构 / 混合模式**：

```powershell
# 异构：短/中/长 20% / 60% / 20%
$env:DATA_DISTRIBUTION="heterogeneous"
node .\loadtest\run-compare-data-distribution.mjs

# 混合：同构 + 异构，50% : 50%
$env:DATA_DISTRIBUTION="mixed"
$env:MIXED_WEIGHT="0.5"
node .\loadtest\run-compare-data-distribution.mjs
```

- **产物**：
  - 某批次目录下的 `compare.csv`，**多一列**：`data_distribution`
  - `samples/*.jsonl` 内包含 `failureReasons` 与 `requestDetails`，可用于失败模式分析。

- **agent 行动要点**：
  - 在 mixed 模式下，注意同时记录 `DATA_DISTRIBUTION` 与 `MIXED_WEIGHT`，便于复现实验。
  - 当用户做“真实场景模拟”时，优先推荐 `mixed`。

---

### 五、结果解读与后处理

#### 5.1 直接查看 `compare.csv`

- 关键字段：
  - `error_rate`：整体错误率
  - `latency_p95_ms`：P95 延迟
  - `actual_rps`：实际吞吐
  - `gc_per_min_avg`、`cpu_usage_avg`、`resource_usage_avg`：资源成本
  - `reject_queue_full` / `reject_budget` / `reject_sampling`：拒绝来源分布

- agent 在总结时应重点对比：
  - 稳定性：`error_rate`、拒绝计数
  - 时延：`latency_p95_ms`
  - 吞吐：`actual_rps`、`throughput_avg`
  - 成本：`cpu_usage_avg`、`gc_per_min_avg`、`resource_usage_avg`

---

#### 5.2 生成可视化报告

- 适用于用户希望看到图表对比时：

```powershell
$env:OUT_DIR="loadtest/results"   # 或具体批次目录
node .\loadtest\generate-report.mjs
```

- 生成文件：
  - `loadtest/results/compare-report.html`（或对应 OUT_DIR 下）

- agent 可提示用户：
  - 在浏览器中打开该 HTML，可以看到多维度折线图（错误率、P95、CPU、吞吐等）。

---

#### 5.3 失败原因分析

- **汇总失败原因（按类型 / 用例）**：

```powershell
node .\loadtest\analyze-failure-reasons.mjs "loadtest/results/batch-006"
```

- **详细失败分析（含 HTTP 状态码 / 延迟分布）**：

```powershell
node .\loadtest\analyze-failures-detailed.mjs "loadtest/results/batch-006"
```

- agent 在解释结果时，应结合 `FAILURE_ANALYSIS.md` 中的结论，例如：
  - 高比例 `TIMEOUT` 可能意味着客户端超时 30s，而服务端仍在处理请求。
  - `HTTP_503` 可能与策略层面的拒绝有关。

---

### 六、与 PowerShell 脚本的关系（给 agent 的选择策略）

- `run-compare.ps1` / `run-all-distributions.ps1` 仍然可用，但：
  - User 提示为 **“优先用 Node 脚本”** 时，agent 应 **首选本 Skill 中的 Node 入口**。
  - 仅当用户明确要求“沿用 PowerShell 流程”或“需要 k6 场景”时，再使用：
    - `.\loadtest\run-compare.ps1 ...`
    - `.\loadtest\run-all-distributions.ps1 ...`

- 若 agent 需要解释差异，可简单说明：
  - PowerShell + k6：更接近传统 k6 场景配置，依赖 k6 安装。
  - Node 脚本：完全基于 Node 自己发请求与采样，更易在 CI / 跨平台环境中统一。

---

### 七、agent 调用本 Skill 的原则

- **在本仓库内需要“压测 Gateway / 对比策略 / 分析稳定性”时，必须先阅读本文件，再选择合适的 Node 脚本入口。**
- 若用户的需求不清晰，agent 应主动向用户确认以下几点后再选脚本：
  1. 是否允许较长时间压测（>5 分钟）？
  2. 是否关心不同 RPS 档位下的表现，还是只要一个典型 RPS？
  3. 是否在意请求 token 分布（同构 vs 异构）？
  4. 是否需要可中断、可追溯的批次记录？
- 根据回答，在 §4 中选择合适入口，并在总结中引用本 Skill 的约定和指标解释。

