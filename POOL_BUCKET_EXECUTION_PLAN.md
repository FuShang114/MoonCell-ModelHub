# 动态分桶资源池改造计划（最终审阅版）

## 0. 目标与约束

### 目标

- 以 `RPM/TPM` 预算为硬约束，构建可解释、可观测、可平滑切换的动态分桶资源池。
- 在高并发下，保证对象分配正确性（CAS 抢占）、限流稳定性（自动释放）、切换连续性（平滑排空）。

### 强约束（不可偏离）

- 资源池对象仅为配置载体，不依赖 SSE 状态，不做续期。
- 分桶更新分两步：
  1. 原子更新路由器（新请求立即生效）
  2. 平滑更新对象池（先删后增，避免放大量）
- 分桶数量支持 5~6 桶，边界与权重可配置。
- 每个对象分配必须使用 CAS 抢占；失败后继续随机采样。
- 采样参数可配置，默认：
  - `samplingRounds = 2`
  - `samplingSize = 3`
- 每个 `ModelInstance` 有只读 `T`（秒），对象占用超时自动释放。

---

## 1. 核心模型定义

## 1.1 数据模型

- **ModelInstance（输入能力）**
  - `rpmLimit`
  - `tpmLimit`
  - `T`（只读展示，不允许用户直接修改）

- **BucketConfig（全局或策略级）**
  - `maxContextK`
  - `bucketCount`（5 或 6）
  - `bucketRanges[]`（每桶 token 上界）
  - `bucketWeights[]`（每桶权重）
  - `dynamicBucketingEnabled`
  - `histogramSampleSize`
  - `bucketUpdateIntervalSeconds`
  - `samplingRounds`
  - `samplingSize`

- **PoolObject（深拷贝对象）**
  - `objectId`
  - `instanceId`
  - `bucketId`
  - `tokenUpperBound`
  - `occupied`（CAS 原子位）
  - `occupiedAtMs`
  - `expireAtMs = occupiedAtMs + T*1000`

## 1.2 运行时模型

- `ACTIVE`：接收新请求
- `DRAINING`：不接新请求，仅回收与排空
- `RETIRED`：无在途对象后释放资源

---

## 2. 对象数计算公式（单实例）

### 符号

- `R` = `rpmLimit`
- `Tpm` = `tpmLimit`
- `B` = 桶集合，`|B| = 5 or 6`
- `U_i` = 桶 `i` 最大 token
- `w_i` = 桶 `i` 权重
- `W = Σ w_i`
- `n_min` = 每桶最小对象数下限（默认 1）

### 2.1 TPM 侧

- `p_i = w_i / W`
- `Tpm_i = Tpm * p_i`
- `n_i_tpm_raw = floor(Tpm_i / U_i)`
- `n_i_tpm = max(n_min, n_i_tpm_raw)`
- `N_tpm = Σ n_i_tpm`

### 2.2 RPM 侧

- `N_rpm = floor(R / 60)`

### 2.3 总对象数

- `N_total = min(N_tpm, N_rpm)`

### 2.4 回分桶

- `n_i_base = floor(N_total * p_i)`
- `L = N_total - Σ n_i_base`
- 用最大余数法分配 `L`
- 得到 `n_i`，且 `Σ n_i = N_total`

### 一句话公式

- `N_total = min( floor(R/60), Σ_i max(n_min, floor((Tpm * w_i / Σw) / U_i)) )`

---

## 3. 路由与分配流程（请求路径）

1. 请求进入，估算 `estimatedTokens`
2. 使用路由器快照判定桶 `bucketId`
3. 在该桶资源池执行随机采样：
   - 外层轮次：`samplingRounds`
   - 每轮采样数：`samplingSize`
4. 对候选对象执行 CAS 抢占：
   - CAS 成功：分配对象，写入 `occupiedAt/expireAt`
   - CAS 失败：继续采样
5. 全部失败则拒绝（限流）

---

## 4. 分桶更新机制（双阶段）

## 4.1 阶段A：原子更新路由器

- 新边界与新权重计算完成后，生成新路由快照。
- 使用原子引用切换：
  - 新请求立即使用新快照
  - 旧请求不受影响

## 4.2 阶段B：平滑更新对象池

- 基于新快照计算每实例目标 `n_i`
- 执行平滑迁移：
  - 先删：优先回收空闲对象到目标
  - 后增：再增量创建缺口对象
- 活跃对象不强杀，等待自然释放到池后收缩

---

## 5. T 字段更新方案（无续期）

### 定位

- `T` 是对象占用超时释放阈值，不是会话保活参数。
- 不依赖 SSE 事件，不做续期。

### 统计输入（建议 15 分钟窗口）

- `allocSuccessRate`
- `casRetryP95`
- `forcedReleaseRate`
- `rejectRate`

### 更新规则（建议每 5 分钟）

1. 计算候选 `T_candidate`（依据重试、强制释放、拒绝率）
2. 平滑更新：`T_new = round(0.8 * T_old + 0.2 * T_candidate)`
3. 迟滞：`|T_new - T_old| < 2` 则不更新
4. 边界：`T ∈ [5,120]`，默认 `T=20`

### 生效规则

- 新分配对象使用 `T_new`
- 已占用对象保持分配时 `T`

---

## 6. 并发与一致性控制

- CAS 是唯一占用入口，禁止非原子写占用位。
- 防重复释放：释放前校验对象占用状态与租约归属。
- 切换一致性：
  - `ACTIVE` 才接新流量
  - `DRAINING` 仅处理回收
  - 在途对象归零后转 `RETIRED`

---

## 7. 接口与前端改造范围

## 7.1 后端

- 设置接口新增：
  - `maxContextK`
  - `bucketCount`
  - `bucketRanges[]`
  - `bucketWeights[]`
  - `samplingRounds`
  - `samplingSize`
- 状态接口新增：
  - 运行时状态（ACTIVE/DRAINING/RETIRED）
  - 当前桶边界、权重、对象总量、每桶对象数、在途数
  - 当前 `T`（只读）

## 7.2 前端 settings

- 分策略展示配置项（TRADITIONAL / OBJECT_POOL）
- OBJECT_POOL 区域支持：
  - 5~6 桶编辑器
  - 权重与边界校验
  - 采样参数配置
  - 运行时状态表展示
  - `T` 只读展示

---

## 8. 实施步骤（按优先级）

1. **P0 正确性**
   - CAS 抢占链路
   - 双阶段更新骨架（原子路由 + 平滑池迁移）
   - 释放一致性与状态机闭环
2. **P1 可配置化**
   - 5~6 桶配置、权重配置、采样配置
   - 公式化对象数计算与回分桶
3. **P2 可观测与优化**
   - 状态面板、诊断指标、性能调优（采样/锁竞争）

---

## 9. 验收清单

- [ ] 分桶配置 5~6 桶生效，边界/权重校验正确
- [ ] 对象总数满足 `min(TPM侧, RPM/60)`
- [ ] 每桶对象数分配总和守恒
- [ ] 路由器更新原子生效
- [ ] 资源池更新平滑（先删后增），无抖动尖峰
- [ ] CAS 抢占失败可重采样且无死循环
- [ ] `T` 自动释放有效，且不依赖 SSE
- [ ] 平滑切换可从 `ACTIVE -> DRAINING -> RETIRED` 完整收敛

# OBJECT_POOL 分桶算法改造计划（持久化）

## 最终口径（已确认）

- 不再使用 `tpm/200000` 这类固定经验除数推导对象数。通过分桶算法初始值直接分配对象实例
- 初始分桶基于“**最大上下文长度（单位 K）**”配置。
- 分桶数不是固定 3 桶，改为 **5~6 桶**，且允许用户自定义桶边界与桶权重。
- 每个 `ModelInstance` 的对象数必须由以下要素计算：
  - `rpm`
  - `tpm`
  - 动态分桶更新后的桶范围（每桶最大 token）
  - 每桶权重
- 计算规则：
  1. 先按 `tpm + 每桶最大 token + 桶权重` 计算理论对象数  
  2. 再与 `rpm/60` 取 `min` 得到最终对象总数  
  3. 再按权重分配到每个桶的对象数
- 根据计算结果深拷贝生成资源池对象，并支持平滑更新（增减对象不能突变）。
- 分桶更新分两步执行：
  1. **原子更新路由器**（新请求立刻走新路由）
  2. **平滑更新资源池**（逐步删/增对象，先删后增，避免放大限流风险）
- 资源池对象是配置载体，不依赖 SSE 返回状态，不做续期。
- 深拷贝后的对象新增状态字段 `occupied`（CAS 更新）：
  - 请求分配对象时 CAS 抢占
  - CAS 失败继续随机采样
- 随机采样参数允许用户配置：
  - `samplingRounds`（默认 `2`）
  - `samplingSize`（默认 `3`）
- 每个 `ModelInstance` 增加只读 `T` 字段（前端可见不可改）：
  - 含义：对象被占用后，`T` 秒自动释放
  - 作用：限流与异常兜底释放
  - 不与 SSE 状态绑定，不做续期

## T 字段更新方案（无续期）

### 定位

- `T` 是“对象占用超时释放”的静态控制参数，非会话保活参数。
- 更新时间粒度：按实例定时更新（建议每 `5` 分钟）。

### 计算输入（近窗口统计，建议 15 分钟）

- `allocSuccessRate`：对象 CAS 抢占成功率
- `casRetryP95`：CAS 重试次数 P95
- `forcedReleaseRate`：到期自动释放占比
- `rejectRate`：资源池拒绝率

### 更新逻辑（不依赖 SSE）

1. 计算候选值（秒）：
   - 当 `casRetryP95` 高、`forcedReleaseRate` 高时上调 `T`
   - 当 `rejectRate` 高但 `forcedReleaseRate` 极低时下调 `T`
2. 平滑更新：
   - `T_new = round(0.8 * T_old + 0.2 * T_candidate)`
3. 迟滞保护：
   - 若 `|T_new - T_old| < 2`，本轮不更新
4. 边界：
   - `T ∈ [5, 120]`
   - 默认 `T = 20`

### 生效方式

- `T` 更新后仅影响新分配对象；
- 已占用对象按分配时刻的 `T` 到期释放；
- 与路由器更新解耦，避免同一时刻双重抖动。

## 单实例对象数计算公式（强约束）

### 符号定义

- `R`：该 `ModelInstance` 的 `rpmLimit`
- `T`：该 `ModelInstance` 的 `tpmLimit`
- `B`：桶集合，`|B| = 5 或 6`
- 对每个桶 `i ∈ B`：
  - `U_i`：该桶最大 token（上界）
  - `w_i`：该桶权重（正整数）
- `W = Σ w_i`
- `n_min`：每桶最小对象数下限（默认 `1`，可配置）

### 第一步：TPM 侧对象数

- 桶占比：`p_i = w_i / W`
- 桶 TPM 预算：`T_i = T * p_i`
- 桶理论对象数：`n_i_tpm_raw = floor(T_i / U_i)`
- 桶对象数（含下限）：`n_i_tpm = max(n_min, n_i_tpm_raw)`
- TPM 侧总对象数：`N_tpm = Σ n_i_tpm`

### 第二步：RPM 侧对象上限

- `N_rpm = floor(R / 60)`

> 说明：如需保守系数可引入 `α`，则 `N_rpm = floor(α * R / 60)`，默认 `α = 1`。

### 第三步：总对象数取最小约束

- `N_total = min(N_tpm, N_rpm)`

### 第四步：总对象数按权重回分桶

- 初分：`n_i_base = floor(N_total * p_i)`
- 余数：`L = N_total - Σ n_i_base`
- 余数分配：按“最大余数法”（或短桶优先策略）将 `L` 分配到各桶
- 最终：`n_i = n_i_base + alloc_i`，且必须满足 `Σ n_i = N_total`

### 一句话总公式

- `N_total = min( floor(R/60), Σ_i max(n_min, floor((T * w_i / Σw) / U_i)) )`

### 边界条件

- 若 `U_i <= 0`：配置非法，拒绝应用
- 若 `R <= 0` 或 `T <= 0`：该实例对象数置 `0`（不承接业务）
- 若 `N_total = 0`：该实例仅保留健康检查，不参与请求分配

### 平滑更新约束

- 扩容：按目标 `n_i` 增量创建对象
- 缩容：仅回收空闲对象，活跃对象完成后再回收
- 禁止一次性重建导致对象池突变

## 当前结论

- 运行时模型与平滑热切换 **已实现一版**，不是从零新写。
- 当前代码已具备：
  - `ACTIVE -> DRAINING -> RETIRED` 状态机
  - 新策略接新流量、老策略排空后回收
  - `acquireLease/releaseLease` 租约链路
  - 策略状态查询接口：`/admin/load-balancing/strategy-statuses`

## 已落地（Done）

1. **运行时模型**
   - `StrategyRuntime` 维护算法实例、状态、租约计数、激活时间。
2. **平滑热切换**
   - 切换算法时旧策略进入 `DRAINING`，新策略进入 `ACTIVE`。
   - 旧策略 `inflightLeases == 0` 后进入 `RETIRED` 并释放资源。
3. **设置项升级**
   - 动态分桶配置项已加入：
     - `dynamicBucketingEnabled`
     - `histogramSampleSize`
     - `bucketUpdateIntervalSeconds`
     - `shortBucketWeight / mediumBucketWeight / longBucketWeight`
4. **前端设置页**
   - 按策略显示不同配置项
   - 新增策略状态列表展示

## 待完善（Next）

1. **平滑切换观测增强**
   - 增加 `drainStartTime/drainDuration` 展示字段
   - 增加“超时未排空”告警与处理策略
2. **分桶配置重构（5~6桶）**
   - 新增 `maxContextK` 配置项（初始分桶依据）
   - 新增可配置桶数组（5~6桶）：`bucketRanges`、`bucketWeights`
   - 前端支持桶数与桶边界/权重编辑与校验
3. **对象数计算重构（核心）**
   - 实现 `modelInstance` 维度对象数公式：
     - `totalByTpm = sum( perBucketObjectsByTpm )`
     - `totalByRpm = floor(rpm / 60)`
     - `totalObjects = min(totalByTpm, totalByRpm)`
   - 再按权重折算每桶对象数，保证总和守恒
4. **资源池平滑更新（先删后增）**
   - 增加对象池版本化（old/new pool 并存窗口）
   - 缩容优先：先回收空闲对象，再处理增量创建
   - 扩容：增量创建；缩容：仅回收空闲对象，活跃对象自然排空
   - 避免一次性重建导致抖动
5. **并发安全复核**
   - 重点检查切换窗口 release 路径、重复释放与计数一致性
   - 检查 CAS 抢占失败后的重采样路径和上限保护

## 验收标准（Checklist）

- [ ] 切换期间无请求丢失，新旧策略状态转换可观察
- [ ] `DRAINING` 最终可收敛到 `RETIRED`，无长期悬挂
- [ ] 5~6桶配置生效，桶边界和权重可动态更新
- [ ] 对象总数满足 `min(tpm侧计算, rpm/60)`，且各桶分配可追踪
- [ ] 资源池更新过程平滑，无明显抖动与突增拒绝
- [ ] 压测下 GC 频率不劣化，且吞吐与稳定性可接受

## 变更优先级

1. P0：对象数计算公式与资源池平滑更新正确性
2. P1：5~6桶配置化与动态更新稳定性
3. P2：切换与并发路径的性能优化（采样/锁竞争）

## 执行快照（持久化，避免遗忘）

### 目标 vs 现状

- **严格公式口径可核对**：已落地  
  - 状态接口已输出 `formulaRpm/formulaTpm/formulaTotal`
  - 同时输出 `totalObjects` 与 `bucketObjectCounts`，可直接对比
- **动态分桶与对象池联动**：已落地  
  - 对象池按桶边界/权重重建，执行“先删后增”
- **平滑热切换状态可观测**：已落地  
  - `ACTIVE -> DRAINING -> RETIRED` 及在途租约可见
- **整流队列可配置可观测**：已落地  
  - `queueCapacity` 配置生效，状态输出 `queueDepth/queueCapacity`
- **平滑更新过程指标化（删/增/占用）**：进行中  
  - 本轮开始执行并落地状态接口 + 前端展示
- **T 自动更新闭环（无续期）**：待执行  
  - 已落地窗口统计与调参逻辑；支持阈值配置化与状态观测

### 下一步行动（优先级）

1. **P0：平滑更新过程指标化收口**
   - 状态输出并前端展示：
     - `lastResizeDeleted`
     - `lastResizeAdded`
     - `activeOccupiedObjects`
2. **P0：T 字段更新闭环（无续期）**
   - 已完成：窗口统计 `allocSuccessRate/casRetryP95/forcedReleaseRate/rejectRate`
   - 已完成：规则更新 `T`（平滑、迟滞、边界约束）
   - 已完成：阈值配置化（调优窗口/CAS采样窗口/拒绝率阈值/强制释放率阈值/CAS重试P95阈值）
3. **P1：公式一致性守护**
   - 已完成对象数公式单测（`ObjectPoolFormulaTest`，覆盖 `N_rpm/N_tpm/N_total` 与每桶守恒）
   - 已完成状态接口契约测试（`LoadBalancerStatusContractTest`，覆盖新增扩展字段非空契约）
4. **P2：运行时可观测增强**
   - 已完成：拒绝原因分布（queueFull/budget/sampling）状态输出与前端展示
   - 已完成：每桶占用率（占用对象数 + 占用率）状态输出与前端展示
   - 已完成：DRAINING 排空耗时（drainDuration）状态输出与前端展示
   - 监控大屏已新增折线图：GC率、CPU、QPS、成功率、失败率、吞吐量、资源使用量
