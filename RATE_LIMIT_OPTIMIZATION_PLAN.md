# 大模型限流逼近与资源利用改造方案

更新时间：2026-02-13 11:43:37

## 1. 背景与目标

基于阿里云百炼的限流规则（主账号维度、模型维度、RPM/TPM 及秒级突发保护），我们的目标不是简单“避开限流”，而是：

- 在不触发大面积失败的前提下，尽可能逼近可用额度上限；
- 将吞吐稳定在“高利用、低抖动”区间；
- 在限流出现时可自动降级、快速恢复，而不是雪崩重试；
- 将限流从“被动报错”变成“可观测、可控制、可优化”的系统能力。

---

## 2. 限流规则（转为 Markdown 归档）

### 2.1 基本原则

- **主账号维度**：按主账号下所有 RAM 子账号、所有业务空间、所有 API-KEY 的调用总和计算。
- **模型独立限流**：不同模型限流互相独立。

### 2.2 常见限流错误语义

- `Requests rate limit exceeded` / `You exceeded your current requests list`
  - 频率触发限流（偏 RPM/RPS）。
- `Allocated quota exceeded` / `You exceeded your current quota`
  - Token 消耗触发限流（偏 TPM/TPS）。
- `Request rate increased too quickly`
  - 未必达到分钟上限，但短时速率激增触发稳定性保护。

### 2.3 关键注意点

- 除了 RPM/TPM，系统可能按秒级 RPS/TPS（约 RPM/60、TPM/60）做保护。
- “一分钟总量未超”并不代表“秒级瞬时不超”。
- 模型监控数据按小时更新，可能有延迟。
- 限流恢复常见在 1 分钟左右，但应以错误信息为准。

### 2.4 官方建议（归纳）

- 选用限流更宽松模型（如稳定版、非快照版）；
- 降频、降 Token；
- 平滑流量（匀速调度、指数退避、队列缓冲）；
- 预置备选模型；
- 大任务拆批；
- 非实时场景采用 Batch API。

---

## 3. 当前网关能力与缺口（针对本项目）

当前项目已有：

- `GatewayService`：统一入口、幂等、下游转发；
- `LoadBalancer`：实例级并发 + 令牌式控制；
- `HeartbeatService`：实例健康检查；
- 管理台可维护模型实例配置。

主要缺口：

- **缺少“账号+模型维度”的统一配额视图**（当前更偏实例维度）；
- **缺少 Token 预算前置控制**（请求前没有 TPM 预算判定）；
- **缺少突发抑制与升速控制**（warm-up/ramp-up）；
- **缺少限流错误分类闭环**（未把供应商报错反馈到调度器）；
- **缺少自动备选模型切换策略**（目前可配实例，但无系统级 fallback 编排）；
- **缺少“逼近上限”的动态策略**（仅固定阈值会保守或震荡）。

---

## 4. 逼近限流值的思考框架（核心）

### 4.1 多预算统一治理，而不是单一 QPS

应同时管理四类预算：

- 请求预算：RPM / RPS；
- Token 预算：TPM / TPS；
- 并发预算：实例并发槽位；
- 稳定性预算：短时斜率（请求增长速度）。

结论：**调度决策应是多约束最小剩余量驱动**，而非只看并发。

### 4.2 “软阈值 + 硬阈值 + 抖动缓冲”三段式

建议：

- 软阈值（例如 80%）：开始减速、优先队列化；
- 预警阈值（例如 90%）：限制低优先级请求；
- 硬阈值（例如 95%+）：只放行高优请求 + fallback。

配合 5%-10% 安全缓冲区，避免监控延迟与估算误差导致越界。

### 4.3 Token 预估优于事后统计

TPM 限制下，必须在请求入队前估算 token：

- 输入 token 可用 tokenizer 或近似规则；
- 输出 token 用历史分位值（如 P75/P90）估算；
- 预算判定使用 `estimated_total_tokens`，完成后再回写真实值校准模型。

### 4.4 平滑优先于重试

`Request rate increased too quickly` 本质是“斜率过大”：

- 先做 admission control（准入控制）；
- 再做匀速调度（每 100ms 分片发放）；
- 最后才是 retry（指数退避 + 抖动）。

### 4.5 限流是路由信号，不只是错误

当某模型出现频率型限流时，说明可将同类请求迁移到备选模型；
当出现 token 型限流时，说明优先做“降 token + 摘要化 + 分批化”。

---

## 5. 改造方案（可落地）

## 5.1 架构新增组件

1) `QuotaPolicyService`
- 职责：维护各模型 RPM/TPM、秒级保护阈值、恢复窗口、fallback 链。
- 数据来源：管理后台配置 + 可选动态下发。

2) `TokenEstimator`
- 职责：估算请求 token 与输出上界；
- 结果：`estimatedInputTokens` / `estimatedOutputTokens` / `estimatedTotalTokens`。

3) `QuotaGovernor`
- 职责：在请求进入下游前做多维预算校验；
- 维度：`model+rpm`、`model+tpm`、`global+rps`、`global+tps`、`instanceConcurrency`；
- 存储：建议 Redis（滑动窗口计数 + 原子脚本）。

4) `AdaptiveScheduler`
- 职责：平滑发放请求，控制瞬时斜率；
- 策略：令牌桶 + 小时间片匀速 + warm-up（逐步升速）。

5) `LimitErrorClassifier`
- 职责：识别下游错误并标准化为内部原因码：
  - `RATE_RPM`
  - `RATE_TPM`
  - `RATE_BURST`
  - `OTHER_PROVIDER_ERROR`

6) `FallbackRouter`
- 职责：按策略切换备选模型（同能力层级）。

## 5.2 请求链路改造

在 `GatewayService.chat()` 前后补充：

1. 请求入站 -> `TokenEstimator` 估算 token；
2. 调 `QuotaGovernor.tryAcquire(model, estimatedTokens)`；
3. 若拒绝：
   - 可排队则入队等待；
   - 不可排队则返回可解释错误（含 `retryAfter` 建议）；
4. 下游执行；
5. 失败时 `LimitErrorClassifier` 分类；
6. 分类结果回写 `QuotaGovernor`（短时降速、熔断、切换 fallback）；
7. 成功后回写真实 token，持续修正估算偏差。

## 5.3 限流策略参数（建议初始值）

- 软阈值：`80%`
- 预警阈值：`90%`
- 硬阈值：`95%`
- 调度时间片：`100ms`
- 突发桶容量：`1.2 * 每秒配额`
- warm-up：`30s` 从 30% 线性爬升到 100%
- 重试策略：
  - 首次退避 `200ms`
  - 指数倍数 `x2`
  - 最大 `3` 次
  - 全程加随机抖动 `0~150ms`

## 5.4 备选模型策略

- 每个主模型配置 `fallbackModels`（按优先级）；
- 触发条件：
  - `RATE_RPM` / `RATE_BURST` 连续 N 次；
  - 当前模型可用预算低于硬阈值；
- 回切条件：
  - 主模型连续 M 个窗口恢复到软阈值以内。

---

## 6. 数据与配置改造建议

建议新增表（或配置中心结构）：

- `model_quota_policy`
  - `model_name`
  - `rpm_limit`
  - `tpm_limit`
  - `rps_soft_limit`
  - `tps_soft_limit`
  - `burst_capacity`
  - `fallback_models` (JSON)
  - `enabled`

- `model_runtime_state`（可只放 Redis）
  - 当前窗口计数
  - 最近限流类型与时间
  - 自适应降速系数（`throttleFactor`）

---

## 7. 分阶段实施计划

### Phase 1（最小可用）

- 增加限流错误分类；
- 增加统一指标埋点（RPM/TPM/限流率/队列等待）；
- 增加模型级配置（静态）。

验收：

- 能区分频率型与 token 型限流；
- 管理台可看到各模型限流原因占比。

### Phase 2（前置预算控制）

- 上线 `TokenEstimator + QuotaGovernor`；
- 对高风险请求做准入拒绝或排队；
- 返回 `retryAfter` 与可解释错误码。

验收：

- 限流失败率下降；
- 成功吞吐稳定提升。

### Phase 3（平滑调度与自适应）

- 上线 `AdaptiveScheduler`；
- 引入 warm-up 与突发抑制；
- 基于最近窗口自动调节 `throttleFactor`。

验收：

- `Request rate increased too quickly` 显著下降；
- 95 分位延迟可控。

### Phase 4（自动 fallback）

- 上线 `FallbackRouter`；
- 支持按错误类型切换模型；
- 支持自动回切主模型。

验收：

- 高峰期总体成功率明显提升；
- 用户侧失败感知降低。

---

## 8. 指标体系（必须落地）

至少埋点以下指标（按模型、业务空间、实例维度切片）：

- 请求数：`req_total`, `req_success`, `req_failed`
- 限流数：`rate_limit_rpm`, `rate_limit_tpm`, `rate_limit_burst`
- token：`input_tokens`, `output_tokens`, `estimated_tokens`, `estimate_error`
- 调度：`queue_depth`, `queue_wait_ms`, `dispatch_rate`
- fallback：`fallback_count`, `fallback_success_rate`
- 用户体验：`p50/p95/p99 latency`, `error_rate`

核心目标指标（建议）：

- 资源利用率：长期 85%+（不以牺牲稳定性为代价）
- 限流失败率：较改造前下降 40%+
- 突发保护错误占比：下降 60%+
- 高峰期成功率：提升 10%-20%（视业务模式）

---

## 9. 风险与对策

- **Token 估算误差大**
  - 对策：先保守估算，后续用真实值闭环校准。
- **过度排队导致时延恶化**
  - 对策：按请求优先级设置最大等待时长，超时快速失败。
- **fallback 模型语义偏差**
  - 对策：按能力分层配置备选，灰度开启并观测质量指标。
- **配置复杂度提升**
  - 对策：先提供默认策略模板，避免每模型手工调参。

---

## 10. 对本项目的直接改造清单（代码级）

建议在当前代码基础上新增：

- `service/QuotaPolicyService.java`
- `service/TokenEstimatorService.java`
- `core/rate/QuotaGovernor.java`
- `core/rate/AdaptiveScheduler.java`
- `core/rate/LimitErrorClassifier.java`
- `core/router/FallbackRouter.java`
- `dto/RateLimitDecision.java`
- `dto/StandardizedProviderError.java`

并调整：

- `GatewayService`：加入“估算 -> 准入 -> 调度 -> 回写”的主链路；
- `AdminController/AdminService`：新增配额策略 CRUD；
- 管理页 `config.html`：新增模型配额、fallback 策略配置项与实时指标面板；
- `schema.sql`：增加配额策略表。

---

## 11. 最终建议

短期先做“可观测 + 可解释 + 前置预算控制”，中期做“平滑调度 + fallback 自动化”，长期做“按业务优先级和成本目标的智能编排”。

这样可以在阿里云限流框架内，稳步逼近上限并充分利用资源，而不是依赖盲目提高并发或被动重试。
