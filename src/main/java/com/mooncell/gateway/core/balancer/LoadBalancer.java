package com.mooncell.gateway.core.balancer;

import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.dto.StrategyStatusDto;
import com.mooncell.gateway.service.InstanceWebClientManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 负载均衡器核心组件
 * 
 * <p>负责从多个模型实例中选择合适的实例处理请求，采用传统策略：
 * <ul>
 *   <li><b>TRADITIONAL</b>：基于 RPM/TPM 预算的随机采样分配</li>
 * </ul>
 * 
 * <p>核心功能：
 * <ul>
 *   <li><b>多池支持</b>：支持按 poolKey 分组管理实例，按配置顺序尝试不同池</li>
 *   <li><b>动态分桶</b>：根据请求 Token 数量将请求分配到不同桶（bucket），支持动态调整桶边界</li>
 *   <li><b>平滑切换</b>：算法或配置变更时平滑切换，旧运行时进入 DRAINING 状态等待完成</li>
 *   <li><b>状态快照</b>：实例刷新时保存运行时状态（请求数、失败数、延迟等），避免状态丢失</li>
 *   <li><b>队列管理</b>：通过队列容量限制并发请求数，防止过载</li>
 * </ul>
 * 
 * <p>工作流程：
 * <ol>
 *   <li>按 orderedPoolKeysList 顺序尝试各池的策略运行时</li>
 *   <li>策略运行时通过随机采样、预算检查选择可用实例</li>
 *   <li>返回选中的实例，由 GatewayService 转发请求</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadBalancer {
    /** 默认资源池键名 */
    private static final String DEFAULT_POOL_KEY = "default";

    private final ModelInstanceMapper modelMapper;
    private final LoadBalancingSettingsStore settingsStore;
    private final InstanceWebClientManager instanceWebClientManager;
    /** 运行时序列号生成器，用于创建唯一运行时 ID */
    private final AtomicLong runtimeSeq = new AtomicLong(0);
    /** 所有策略运行时列表（支持多池） */
    private final CopyOnWriteArrayList<StrategyRuntime> runtimes = new CopyOnWriteArrayList<>();
    /** 按池键索引的策略运行时映射 */
    private final ConcurrentHashMap<String, StrategyRuntime> runtimeByPool = new ConcurrentHashMap<>();
    /** 资源池尝试顺序列表，按此顺序尝试各池获取实例 */
    private volatile List<String> orderedPoolKeysList = List.of(DEFAULT_POOL_KEY);
    /** 分桶與直方圖管理組件 */
    private final BucketManager bucketManager = new BucketManager();
    /** StrategyRuntime 管理（創建/切換/清理）組件 */
    private final StrategyRuntimeManager runtimeManager = new StrategyRuntimeManager();
    /** 實例刷新與 RuntimeState 快照/恢復組件 */
    private final InstanceRefresher instanceRefresher = new InstanceRefresher();
    /** 当前负载均衡配置 */
    private volatile LoadBalancingSettings settings = LoadBalancingSettings.defaultSettings();
    /** 当前活跃的运行时（指向 orderedPoolKeysList 第一个池的运行时） */
    private volatile StrategyRuntime activeRuntime;
    /** 是否接受新请求（用于优雅关闭） */
    private volatile boolean acceptingNewRequests = true;

    /**
     * 初始化负载均衡器
     * 
     * <p>从配置存储加载设置，解析分桶参数和池顺序，创建策略运行时并刷新实例列表。
     * 如果配置存储中没有设置，则使用默认配置。
     */
    @PostConstruct
    public void init() {
        LoadBalancingSettings loaded = settingsStore.load().orElse(null);
        if (loaded != null) {
            normalizeSettings(loaded);
            settings = loaded.copy();
        } else {
            settings = LoadBalancingSettings.defaultSettings();
        }
        bucketManager.initFromSettings(settings);
        orderedPoolKeysList = parseOrderedPoolKeys(settings.getOrderedPoolKeys());

        LoadBalancingAlgorithm algorithm = settings.getAlgorithm() == null
                ? LoadBalancingAlgorithm.TRADITIONAL
                : settings.getAlgorithm();
        runtimeManager.initializeRuntimes(algorithm);
        refreshInstances();
    }

    /**
     * 更新负载均衡配置
     * 
     * <p>支持热更新配置，如果算法或池顺序发生变化，会平滑切换：
     * <ul>
     *   <li>旧运行时标记为 DRAINING 状态，等待现有请求完成</li>
     *   <li>创建新的运行时并激活</li>
     *   <li>清理已退役的运行时</li>
     * </ul>
     * 
     * <p>如果只是参数调整（如采样数、队列容量等），则直接更新现有运行时的配置。
     * 
     * @param newSettings 新的负载均衡配置
     */
    public synchronized void updateSettings(LoadBalancingSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        normalizeSettings(newSettings);
        LoadBalancingAlgorithm newAlgorithm = newSettings.getAlgorithm() == null
                ? LoadBalancingAlgorithm.TRADITIONAL : newSettings.getAlgorithm();
        settings = newSettings.copy();
        bucketManager.updateFromSettings(settings);
        List<String> newOrderedKeys = parseOrderedPoolKeys(settings.getOrderedPoolKeys());

        runtimeManager.applySettingsUpdate(newAlgorithm, newOrderedKeys);
    }

    /**
     * 规范化配置参数
     * 
     * <p>确保所有配置参数在有效范围内，调用各 setter 方法触发参数校验和修正。
     * 
     * @param cfg 待规范化的配置对象
     */
    private void normalizeSettings(LoadBalancingSettings cfg) {
        cfg.setSampleCount(cfg.getSampleCount());
        cfg.setSamplingRounds(cfg.getSamplingRounds());
        cfg.setSamplingSize(cfg.getSamplingSize());
        cfg.setBucketCount(cfg.getBucketCount());
        cfg.setMaxContextK(cfg.getMaxContextK());
        cfg.setHistogramSampleSize(cfg.getHistogramSampleSize());
        cfg.setBucketUpdateIntervalSeconds(cfg.getBucketUpdateIntervalSeconds());
        cfg.setBucketUpdateIntervalMinSeconds(cfg.getBucketUpdateIntervalMinSeconds());
        cfg.setBucketUpdateIntervalMaxSeconds(cfg.getBucketUpdateIntervalMaxSeconds());
        cfg.setOrderedPoolKeys(cfg.getOrderedPoolKeys());
        cfg.setQueueCapacity(cfg.getQueueCapacity());
        cfg.setTTuneIntervalSeconds(cfg.getTTuneIntervalSeconds());
        cfg.setTCasRetrySampleSize(cfg.getTCasRetrySampleSize());
        cfg.setTRejectHighThreshold(cfg.getTRejectHighThreshold());
        cfg.setTForcedReleaseHighThreshold(cfg.getTForcedReleaseHighThreshold());
        cfg.setTCasRetryP95HighThreshold(cfg.getTCasRetryP95HighThreshold());
        cfg.setBucketRanges(cfg.getBucketRanges());
        cfg.setBucketWeights(cfg.getBucketWeights());
        cfg.setShortBucketWeight(cfg.getShortBucketWeight());
        cfg.setMediumBucketWeight(cfg.getMediumBucketWeight());
        cfg.setLongBucketWeight(cfg.getLongBucketWeight());
    }

    public LoadBalancingSettings getSettings() {
        LoadBalancingSettings copy = settings.copy();
        StrategyRuntime runtime = activeRuntime;
        copy.setAlgorithm(runtime == null ? LoadBalancingAlgorithm.TRADITIONAL : runtime.algorithm);
        return copy;
    }

    /**
     * 刷新实例列表
     * 
     * <p>从数据库加载所有实例，并执行以下操作：
     * <ol>
     *   <li>快照当前所有策略运行时中的实例状态（请求数、失败数、延迟等）</li>
     *   <li>将快照状态应用到新加载的实例上，避免状态丢失</li>
     *   <li>按 poolKey 分组实例</li>
     *   <li>通知各策略运行时刷新其管理的实例列表</li>
     * </ol>
     * 
     * <p>状态快照机制确保在实例刷新过程中不会丢失运行时统计信息，这对于
     * 健康检查、熔断器等功能的正确性至关重要。
     */
    public synchronized void refreshInstances() {
        instanceRefresher.refreshInstances();
    }

    /**
     * 快照所有实例的运行时状态
     * 
     * <p>遍历所有策略运行时，收集每个实例的运行时状态（请求计数、失败计数、
     * 总延迟、最后使用时间、最后失败时间、最后心跳时间、熔断器状态等），
     * 用于在实例刷新时恢复状态。
     * 
     * @return 按实例 ID 索引的状态快照映射
     */

    /**
     * 停止接受新请求
     * 
     * <p>用于优雅关闭，设置后 getNextAvailableInstance 将返回 null。
     */
    public void stopAcceptingNewRequests() {
        this.acceptingNewRequests = false;
    }

    /**
     * 获取下一个可用实例
     * 
     * <p>核心方法，根据请求的 Token 数量选择可用实例：
     * <ol>
     *   <li>根据 estimatedTokens 确定所属的 bucket</li>
     *   <li>可能触发动态分桶边界更新（如果启用）</li>
     *   <li>按 orderedPoolKeysList 顺序尝试各池的策略运行时</li>
     *   <li>每个运行时先尝试进入队列（受 queueCapacity 限制）</li>
     *   <li>调用策略的 acquire 方法获取实例</li>
     *   <li>如果成功则返回实例，否则尝试下一个池</li>
     * </ol>
     * 
     * @param estimatedTokens 预估的 Token 数量（输入+输出）
     * @return 选中的模型实例，如果无可用实例则返回 null
     */
    public ModelInstance getNextAvailableInstance(int estimatedTokens) {
        if (!acceptingNewRequests) {
            return null;
        }
        int tokens = Math.max(1, estimatedTokens);
        RequestBucket bucket = resolveBucket(tokens);
        for (String poolKey : orderedPoolKeysList) {
            StrategyRuntime runtime = runtimeByPool.get(poolKey);
            if (runtime == null || runtime.state != RuntimeState.ACTIVE) {
                continue;
            }
            boolean queueEntered = runtime.tryEnterQueue(settings.getQueueCapacity());
            if (!queueEntered) {
                runtime.rejectQueueFull.incrementAndGet();
                continue;
            }
            try {
                StrategyAcquire acquire = runtime.strategy.acquire(tokens, bucket);
                if (acquire != null && acquire.instance() != null) {
                    return acquire.instance();
                }
            } finally {
                runtime.leaveQueue();
            }
        }
        return null;
    }

    /**
     * 获取下一个可用实例（使用默认 Token 估算值）
     * 
     * @return 选中的模型实例，如果无可用实例则返回 null
     */
    public ModelInstance getNextAvailableInstance() {
        return getNextAvailableInstance(256);
    }

    /**
     * 获取所有实例列表
     * 
     * @return 所有策略运行时管理的实例列表
     */
    public List<ModelInstance> getInstanceList() {
        List<ModelInstance> list = new ArrayList<>();
        for (StrategyRuntime runtime : runtimeByPool.values()) {
            if (runtime != null) {
                list.addAll(runtime.strategy.getInstances());
            }
        }
        return list;
    }

    public QueueStats getStats() {
        int total = 0, healthy = 0, availableRpm = 0, availableTpm = 0;
        long lastReset = System.currentTimeMillis();
        String algorithm = LoadBalancingAlgorithm.TRADITIONAL.name();
        for (StrategyRuntime runtime : runtimeByPool.values()) {
            if (runtime == null) {
                continue;
            }
            QueueStats s = runtime.strategy.getStats();
            total += s.getTotalInstances();
            healthy += s.getHealthyInstances();
            availableRpm += s.getAvailableRpm();
            availableTpm += s.getAvailableTpm();
            if (s.getLastWindowReset() > 0) {
                lastReset = Math.max(lastReset, s.getLastWindowReset());
            }
            algorithm = runtime.algorithm.name();
        }
        QueueStats stats = new QueueStats(total, healthy, availableRpm, availableTpm, lastReset);
        stats.setAlgorithm(activeRuntime != null ? activeRuntime.algorithm.name() : algorithm);
        return stats;
    }

    /**
     * 获取所有策略运行时的详细状态
     * 
     * <p>返回每个运行时的完整状态信息，包括运行时 ID、算法、状态、队列深度、拒绝分布等。
     * 
     * @return 策略状态列表
     */
    public List<StrategyStatusDto> getStrategyStatuses() {
        List<StrategyStatusDto> result = new ArrayList<>();
        for (StrategyRuntime runtime : runtimes) {
            List<Integer> ranges = bucketManager.getActiveBucketRanges();
            List<Integer> weights = bucketManager.getActiveBucketWeights();
            StrategyStatusDto dto = new StrategyStatusDto();
            dto.setRuntimeId(runtime.runtimeId);
            dto.setAlgorithm(runtime.algorithm.name());
            dto.setState(runtime.state.name());
            dto.setSinceEpochMs(runtime.activatedAtMs);
            dto.setShortBoundaryTokens(ranges.isEmpty() ? null : ranges.get(0));
            dto.setMediumBoundaryTokens(ranges.size() < 2 ? null : ranges.get(1));
            dto.setShortWeight(weights.isEmpty() ? null : weights.get(0));
            dto.setMediumWeight(weights.size() < 2 ? null : weights.get(1));
            dto.setLongWeight(weights.isEmpty() ? null : weights.get(weights.size() - 1));
            dto.setBucketCount(ranges.size());
            dto.setBucketRanges(joinInts(ranges));
            dto.setBucketWeights(joinInts(weights));
            dto.setQueueDepth(runtime.queueDepth.get());
            dto.setQueueCapacity(runtime.queueCapacity.get());
            StrategyMetrics metrics = runtime.strategy.snapshotMetrics();
            dto.setDrainDurationMs(runtime.state == RuntimeState.DRAINING && runtime.drainSinceMs > 0
                    ? Math.max(0, System.currentTimeMillis() - runtime.drainSinceMs)
                    : null);
            dto.setRejectQueueFull(runtime.rejectQueueFull.get());
            dto.setRejectBudget(metrics.rejectBudgetCount());
            dto.setRejectSampling(metrics.rejectSamplingCount());
            result.add(dto);
        }
        return result;
    }

    private String joinInts(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    /**
     * StrategyRuntime 管理器
     *
     * <p>負責創建、切換與清理策略運行時，封裝與 {@link StrategyRuntime} 相關的生命周期管理邏輯：
     * <ul>
     *   <li>初始化時為每個池創建運行時並激活</li>
     *   <li>配置熱更新時根據算法/池順序變更決定是否平滑切換</li>
     *   <li>將舊運行時標記為 DRAINING 並在合適時清理</li>
     * </ul>
     */
    private class StrategyRuntimeManager {

        /**
         * 初始化所有池對應的策略運行時。
         *
         * @param algorithm 當前配置指定的負載均衡算法
         */
        void initializeRuntimes(LoadBalancingAlgorithm algorithm) {
            runtimes.clear();
            runtimeByPool.clear();
            for (String poolKey : orderedPoolKeysList) {
                StrategyRuntime runtime = createRuntime(algorithm, settings.copy());
                runtime.state = RuntimeState.ACTIVE;
                runtime.strategy.onActivate(settings.copy());
                runtimeByPool.put(poolKey, runtime);
                runtimes.add(runtime);
            }
            activeRuntime = orderedPoolKeysList.isEmpty() ? null : runtimeByPool.get(orderedPoolKeysList.get(0));
        }

        /**
         * 在配置更新後應用對運行時的變更。
         *
         * <p>如果算法或池順序發生變化，執行平滑切換：
         * <ol>
         *   <li>將當前所有運行時標記為 DRAINING</li>
         *   <li>使用新算法和池順序重建運行時</li>
         *   <li>刷新實例列表並清理 DRAINING 運行時</li>
         * </ol>
         * 如果僅為參數調整，則直接下發新配置到現有運行時。
         *
         * @param newAlgorithm 新算法
         * @param newOrderedKeys 新的池順序
         */
        void applySettingsUpdate(LoadBalancingAlgorithm newAlgorithm, List<String> newOrderedKeys) {
            StrategyRuntime current = activeRuntime;
            boolean algorithmChange = current != null && newAlgorithm != current.algorithm;
            boolean poolOrderChange = !newOrderedKeys.equals(orderedPoolKeysList);

            if (algorithmChange || poolOrderChange) {
                if (algorithmChange) {
                    log.info("Smooth switch load balancing algorithm: {} -> {}",
                            current != null ? current.algorithm : null, newAlgorithm);
                }
                for (StrategyRuntime rt : runtimeByPool.values()) {
                    rt.markDraining(System.currentTimeMillis());
                }
                orderedPoolKeysList = newOrderedKeys;
                runtimes.clear();
                runtimeByPool.clear();
                for (String poolKey : orderedPoolKeysList) {
                    StrategyRuntime next = createRuntime(newAlgorithm, settings.copy());
                    next.state = RuntimeState.ACTIVE;
                    next.strategy.onActivate(settings.copy());
                    runtimeByPool.put(poolKey, next);
                    runtimes.add(next);
                }
                activeRuntime = orderedPoolKeysList.isEmpty() ? null : runtimeByPool.get(orderedPoolKeysList.get(0));
                refreshInstances();
                cleanupDrainingRuntimes();
            } else {
                for (StrategyRuntime rt : runtimeByPool.values()) {
                    rt.settings = settings.copy();
                    rt.strategy.onSettingsChanged(settings.copy());
                }
            }
        }

        /**
         * 清理處於 DRAINING 狀態的運行時。
         *
         * <p>將已標記為 DRAINING 的運行時標記為 RETIRED 並從列表中移除，
         * 完成平滑切換的清理工作。
         */
    private void cleanupDrainingRuntimes() {
        for (StrategyRuntime runtime : new ArrayList<>(runtimes)) {
            if (runtime.state != RuntimeState.DRAINING) {
                continue;
            }
            runtime.strategy.onDeactivate();
            runtime.state = RuntimeState.RETIRED;
            runtimes.remove(runtime);
            }
        }
    }

    /**
     * 创建策略运行时
     * 
     * <p>根据算法类型创建对应的策略实现，并包装为 StrategyRuntime。
     * 
     * @param algorithm 负载均衡算法
     * @param runtimeSettings 运行时配置
     * @return 新创建的策略运行时
     */
    private StrategyRuntime createRuntime(LoadBalancingAlgorithm algorithm, LoadBalancingSettings runtimeSettings) {
        LoadBalancingStrategy strategy = new TraditionalStrategy();
        return new StrategyRuntime(
                "rt-" + runtimeSeq.incrementAndGet(),
                algorithm,
                runtimeSettings.copy(),
                strategy
        );
    }

    /**
     * 根据 Token 数量解析所属分桶。
     *
     * <p>委託給 {@link BucketManager} 計算分桶索引，然後映射到 {@link RequestBucket}。
     *
     * @param estimatedTokens 预估 Token 数量
     * @return 对应的请求分桶
     */
    private RequestBucket resolveBucket(int estimatedTokens) {
        int idx = bucketManager.resolveBucketIndex(estimatedTokens);
        return RequestBucket.ofIndex(idx);
    }

    /**
     * 解析有序资源池键列表
     * 
     * <p>从 CSV 字符串解析资源池键，去重并保持顺序。
     * 如果为空或无效，返回包含默认池键的列表。
     * 
     * @param csv 逗号分隔的资源池键字符串
     * @return 有序的资源池键列表
     */
    private List<String> parseOrderedPoolKeys(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(DEFAULT_POOL_KEY);
        }
        List<String> list = new ArrayList<>();
        for (String part : csv.split(",")) {
            String key = part.trim();
            if (!key.isEmpty() && !list.contains(key)) {
                list.add(key);
            }
        }
        return list.isEmpty() ? List.of(DEFAULT_POOL_KEY) : list;
    }

    /**
     * 负载均衡策略接口
     * 
     * <p>定义策略的生命周期方法和核心选择逻辑。
     */
    private interface LoadBalancingStrategy {
        void onActivate(LoadBalancingSettings settings);
        void onDeactivate();
        void onSettingsChanged(LoadBalancingSettings settings);
        void refreshInstances(List<ModelInstance> instances, LoadBalancingSettings settings);
        StrategyAcquire acquire(int estimatedTokens, RequestBucket bucket);
        StrategyMetrics snapshotMetrics();
        List<ModelInstance> getInstances();
        QueueStats getStats();
    }

    /**
     * 基于 Token 预算的策略基类
     * 
     * <p>提供通用的实例包装、采样、统计等功能，供具体策略实现继承。
     */
    private abstract static class BaseTokenStrategy implements LoadBalancingStrategy {
        protected final CopyOnWriteArrayList<InstanceWrapper> wrappers = new CopyOnWriteArrayList<>();
        protected volatile LoadBalancingSettings settings = LoadBalancingSettings.defaultSettings();

        @Override
        public void onActivate(LoadBalancingSettings settings) {
            this.settings = settings.copy();
        }

        @Override
        public void onDeactivate() {
            wrappers.clear();
        }

        @Override
        public void onSettingsChanged(LoadBalancingSettings settings) {
            this.settings = settings.copy();
        }

        @Override
        public void refreshInstances(List<ModelInstance> instances, LoadBalancingSettings settings) {
            this.settings = settings.copy();
            wrappers.clear();
            for (ModelInstance instance : instances) {
                InstanceWrapper wrapper = createWrapper(instance, this.settings);
                wrappers.add(wrapper);
            }
            rebuildWrapperIndex();
        }

        /**
         * 重建包装器索引（预留，当前策略未使用）
         */
        protected void rebuildWrapperIndex() {
            // Traditional 策略无需索引
        }

        /**
         * 创建实例包装器
         */
        protected InstanceWrapper createWrapper(ModelInstance instance, LoadBalancingSettings settings) {
            return new InstanceWrapper(instance);
        }

        /**
         * 获取采样数量
         * 
         * @return 采样数量（至少为 1）
         */
        protected int sampleCount() {
            return Math.max(1, settings.getSampleCount());
        }

        /**
         * 随机采样实例包装器
         * 
         * <p>从所有实例中随机采样指定数量的实例，用于负载均衡选择。
         * 采样数量由 settings.sampleCount 控制。
         * 
         * @return 采样后的实例包装器列表
         */
        protected List<InstanceWrapper> sampleWrappers() {
            if (wrappers.isEmpty()) {
                return List.of();
            }
            int size = wrappers.size();
            int count = Math.min(sampleCount(), size);
            Set<Integer> seen = new HashSet<>();
            List<InstanceWrapper> samples = new ArrayList<>(count);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            while (samples.size() < count && seen.size() < size) {
                int idx = random.nextInt(size);
                if (seen.add(idx)) {
                    samples.add(wrappers.get(idx));
                }
            }
            return samples;
        }

        @Override
        public List<ModelInstance> getInstances() {
            return wrappers.stream().map(InstanceWrapper::instance).collect(Collectors.toList());
        }

        @Override
        public QueueStats getStats() {
            int total = wrappers.size();
            int healthy = (int) wrappers.stream().filter(InstanceWrapper::isHealthy).count();
            int availableRpm = wrappers.stream().filter(InstanceWrapper::isHealthy).mapToInt(InstanceWrapper::availableRpm).sum();
            int availableTpm = wrappers.stream().filter(InstanceWrapper::isHealthy).mapToInt(InstanceWrapper::availableTpm).sum();
            return new QueueStats(total, healthy, availableRpm, availableTpm, System.currentTimeMillis());
        }

        @Override
        public StrategyMetrics snapshotMetrics() {
            return StrategyMetrics.empty();
        }
    }

    /**
     * 传统负载均衡策略
     * 
     * <p>基于 RPM/TPM 预算的简单采样分配策略：
     * <ul>
     *   <li>每轮随机采样一批实例</li>
     *   <li>对采样结果进行随机打乱</li>
     *   <li>按顺序尝试每个实例的简单预算模型（60秒窗口计数）</li>
     *   <li>如果预算允许则分配，否则尝试下一轮</li>
     * </ul>
     * 
     * <p>简单预算模型：以 60 秒为窗口，统计窗口内已使用的 RPM/TPM，
     * 如果新增请求会超出限制则拒绝。窗口滚动时重置计数。
     */
    private static class TraditionalStrategy extends BaseTokenStrategy {
        @Override
        public StrategyAcquire acquire(int estimatedTokens, RequestBucket bucket) {
            int rounds = Math.max(1, settings.getSamplingRounds());
            boolean budgetRejected = false;
            boolean samplingRejected = false;
            for (int round = 0; round < rounds; round++) {
                List<InstanceWrapper> samples = sampleWrappers();
                if (samples.isEmpty()) {
                    samplingRejected = true;
                    break;
                }
                // 传统策略：每轮随机采样一批实例，按采样顺序尝试首次分配，
                // 使用简单的“每分钟计数”预算模型加锁扣减，不再依赖令牌桶。
                for (int i = samples.size() - 1; i > 0; i--) {
                    int j = ThreadLocalRandom.current().nextInt(i + 1);
                    InstanceWrapper tmp = samples.get(i);
                    samples.set(i, samples.get(j));
                    samples.set(j, tmp);
                }
                for (InstanceWrapper wrapper : samples) {
                    if (wrapper.tryAcquireSimpleBudget(estimatedTokens)) {
                        return new StrategyAcquire(wrapper.instance());
                    }
                    budgetRejected = true;
                }
            }
            if (samplingRejected) {
                rejectSamplingCount.incrementAndGet();
            } else if (budgetRejected) {
                rejectBudgetCount.incrementAndGet();
            } else {
                rejectSamplingCount.incrementAndGet();
            }
            return null;
        }

        @Override
        public StrategyMetrics snapshotMetrics() {
            return new StrategyMetrics(rejectBudgetCount.get(), rejectSamplingCount.get());
        }

        private final AtomicLong rejectBudgetCount = new AtomicLong(0);
        private final AtomicLong rejectSamplingCount = new AtomicLong(0);
    }

    /**
     * 实例包装器
     * 
     * <p>封装模型实例的运行时状态和资源管理：
     * <ul>
     *   <li><b>Token 预算</b>：RPM/TPM 的令牌桶实现（平滑补充）</li>
     *   <li><b>简单预算</b>：60 秒窗口计数模型，用于预算检查</li>
     * </ul>
     */
    private static class InstanceWrapper {
        private final ModelInstance instance;
        private final Object tokenLock = new Object();
        private double rpmTokens;
        private double tpmTokens;
        private long lastRefillNanos = System.nanoTime();
        // 简单预算窗口（按分钟计数）
        private long simpleBudgetWindowStartMs = System.currentTimeMillis();
        private int simpleBudgetUsedRpm = 0;
        private int simpleBudgetUsedTpm = 0;

        InstanceWrapper(ModelInstance instance) {
            this.instance = instance;
            this.rpmTokens = instance.getEffectiveRpmLimit();
            this.tpmTokens = instance.getEffectiveTpmLimit();
        }

        /**
         * 仅供 Traditional 策略使用的简化预算模型：
         * - 以当前时间窗口（60s）为界，统计本窗口内已使用的 RPM/TPM；
         * - 若新增一次请求会超出实例的有效 RPM/TPM 上限，则拒绝；
         * - 不做平滑补充，窗口滚动时重置计数。
         */
        boolean tryAcquireSimpleBudget(int estimatedTokens) {
            synchronized (tokenLock) {
                if (!isHealthy()) {
                    return false;
                }
                long now = System.currentTimeMillis();
                long elapsed = now - simpleBudgetWindowStartMs;
                if (elapsed >= 60_000L || elapsed < 0L) {
                    simpleBudgetWindowStartMs = now;
                    simpleBudgetUsedRpm = 0;
                    simpleBudgetUsedTpm = 0;
                }
                int rpmLimit = Math.max(0, instance.getEffectiveRpmLimit());
                int tpmLimit = Math.max(0, instance.getEffectiveTpmLimit());
                int nextRpm = simpleBudgetUsedRpm + 1;
                int nextTpm = simpleBudgetUsedTpm + Math.max(0, estimatedTokens);
                if (rpmLimit > 0 && nextRpm > rpmLimit) {
                    return false;
                }
                if (tpmLimit > 0 && nextTpm > tpmLimit) {
                    return false;
                }
                simpleBudgetUsedRpm = nextRpm;
                simpleBudgetUsedTpm = nextTpm;
                return true;
            }
        }

        /**
         * 补充 Token 预算
         * 
         * <p>根据时间流逝平滑补充 RPM 和 TPM 令牌，每秒补充 limit/60。
         */
        private void refillTokens() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            double sec = elapsed / 1_000_000_000.0d;
            rpmTokens = Math.min(instance.getEffectiveRpmLimit(), rpmTokens + sec * (instance.getEffectiveRpmLimit() / 60.0d));
            tpmTokens = Math.min(instance.getEffectiveTpmLimit(), tpmTokens + sec * (instance.getEffectiveTpmLimit() / 60.0d));
            lastRefillNanos = now;
        }

        boolean isHealthy() {
            return instance.isHealthy();
        }

        int availableRpm() {
            synchronized (tokenLock) {
                refillTokens();
                return (int) Math.floor(Math.max(0, rpmTokens));
            }
        }

        int availableTpm() {
            synchronized (tokenLock) {
                refillTokens();
                return (int) Math.floor(Math.max(0, tpmTokens));
            }
        }

        ModelInstance instance() {
            return instance;
        }
    }

    /**
     * 队列统计信息
     * 
     * <p>包含负载均衡器的整体统计信息，用于监控和展示。
     */
    public static class QueueStats {
        private final int totalInstances;
        private final int healthyInstances;
        private final int availableRpm;
        private final int availableTpm;
        private final long lastWindowReset;
        private String algorithm;

        public QueueStats(int totalInstances, int healthyInstances, int availableRpm,
                          int availableTpm, long lastWindowReset) {
            this.totalInstances = totalInstances;
            this.healthyInstances = healthyInstances;
            this.availableRpm = availableRpm;
            this.availableTpm = availableTpm;
            this.lastWindowReset = lastWindowReset;
        }

        public int getTotalInstances() { return totalInstances; }
        public int getHealthyInstances() { return healthyInstances; }
        public int getAvailableRpm() { return availableRpm; }
        public int getAvailableTpm() { return availableTpm; }
        public long getLastWindowReset() { return lastWindowReset; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    }

    /**
     * 请求分桶枚举
     * 
     * <p>根据请求的 Token 数量将请求分配到不同的 bucket，用于：
     * <ul>
     *   <li>对象池策略的资源分配和隔离</li>
     *   <li>动态分桶边界的调整</li>
     * </ul>
     */
    private enum RequestBucket {
        B1(0),
        B2(1),
        B3(2),
        B4(3),
        B5(4),
        B6(5);

        private final int idx;

        RequestBucket(int idx) {
            this.idx = idx;
        }

        static RequestBucket ofIndex(int index) {
            int safe = Math.max(0, Math.min(5, index));
            for (RequestBucket b : values()) {
                if (b.idx == safe) {
                    return b;
                }
            }
            return B1;
        }
    }

    /**
     * 运行时状态枚举
     * 
     * <p>用于平滑切换机制：
     * <ul>
     *   <li>ACTIVE：正常运行，接受新请求</li>
     *   <li>DRAINING：正在排空，等待现有请求完成</li>
     *   <li>RETIRED：已退役，等待清理</li>
     * </ul>
     */
    private enum RuntimeState {
        ACTIVE,
        DRAINING,
        RETIRED
    }

    /**
     * 策略运行时
     * 
     * <p>封装策略实例和运行时状态，包括：
     * <ul>
     *   <li>策略实现和算法类型</li>
     *   <li>运行时状态（ACTIVE/DRAINING/RETIRED）</li>
     *   <li>队列管理（深度、容量、拒绝计数）</li>
     *   <li>激活时间戳</li>
     * </ul>
     */
    private static class StrategyRuntime {
        private final String runtimeId;
        private final LoadBalancingAlgorithm algorithm;
        private volatile LoadBalancingSettings settings;
        private final LoadBalancingStrategy strategy;
        private volatile RuntimeState state;
        private final long activatedAtMs;
        StrategyRuntime(String runtimeId, LoadBalancingAlgorithm algorithm, LoadBalancingSettings settings,
                        LoadBalancingStrategy strategy) {
            this.runtimeId = runtimeId;
            this.algorithm = algorithm;
            this.settings = settings;
            this.strategy = strategy;
            this.state = RuntimeState.ACTIVE;
            this.activatedAtMs = System.currentTimeMillis();
            this.queueCapacity.set(Math.max(1, settings.getQueueCapacity()));
        }

        /**
         * 尝试进入队列
         * 
         * <p>使用 CAS 操作原子性地增加队列深度，如果超过容量则拒绝。
         * 
         * @param configuredCapacity 配置的队列容量
         * @return 是否成功进入队列
         */
        boolean tryEnterQueue(int configuredCapacity) {
            int cap = Math.max(1, configuredCapacity);
            queueCapacity.set(cap);
            int current;
            do {
                current = queueDepth.get();
                if (current >= cap) {
                    return false;
                }
            } while (!queueDepth.compareAndSet(current, current + 1));
            return true;
        }

        /**
         * 离开队列
         * 
         * <p>使用 CAS 操作原子性地减少队列深度。
         */
        void leaveQueue() {
            int current;
            do {
                current = queueDepth.get();
                if (current <= 0) {
                    return;
                }
            } while (!queueDepth.compareAndSet(current, current - 1));
        }

        private final AtomicInteger queueDepth = new AtomicInteger(0);
        private final AtomicInteger queueCapacity = new AtomicInteger(128);
        private final AtomicLong rejectQueueFull = new AtomicLong(0);
        private volatile long drainSinceMs = 0L;

        /**
         * 标记为 DRAINING 状态
         * 
         * <p>用于平滑切换，标记后不再接受新请求，等待现有请求完成。
         * 
         * @param nowMs 当前时间戳（毫秒）
         */
        void markDraining(long nowMs) {
            this.state = RuntimeState.DRAINING;
            this.drainSinceMs = nowMs;
        }
    }

    /**
     * 實例刷新與 RuntimeState 快照/恢復邏輯。
     *
     * <p>將實例列表刷新、狀態快照和恢復的職責集中在一處，避免 LoadBalancer 本身過於臃腫。
     * 該類依賴外部的 {@link ModelInstanceMapper}、{@link StrategyRuntime} 列表和當前配置。
     */
    private class InstanceRefresher {
        /**
         * 刷新實例列表並在必要時恢復運行時狀態。
         *
         * <p>流程：
         * <ol>
         *   <li>從資料庫獲取最新的實例列表</li>
         *   <li>對當前所有策略中的實例做狀態快照</li>
         *   <li>將快照應用到新列表中的實例，盡量保留運行時統計</li>
         *   <li>按 poolKey 分組並分發給各策略運行時</li>
         * </ol>
         */
        void refreshInstances() {
            List<ModelInstance> instances = modelMapper.findAll();
            if (instances == null) {
                instances = List.of();
            }
            // 先快照当前所有策略中的实例状态（包括所有 pool）
            Map<Long, RuntimeStateSnapshot> snapshotById = snapshotRuntimeStates();
            log.debug("快照了 {} 个实例的运行时状态", snapshotById.size());

            Map<String, List<ModelInstance>> byPool = new LinkedHashMap<>();
            int restoredCount = 0;
            int newInstanceCount = 0;

            for (ModelInstance instance : instances) {
                instance.ensureRuntimeState();
                if (instance.getId() == null) {
                    continue;
                }
                RuntimeStateSnapshot snapshot = snapshotById.get(instance.getId());
                if (snapshot != null) {
                    applyRuntimeState(instance, snapshot);
                    restoredCount++;
                    log.debug("恢复实例 {} (ID: {}) 的运行时状态: requestCount={}, failureCount={}",
                            instance.getName(), instance.getId(), snapshot.requestCount(), snapshot.failureCount());
                } else {
                    newInstanceCount++;
                    log.debug("实例 {} (ID: {}) 没有找到快照状态，使用默认值（可能是新实例或首次刷新）",
                            instance.getName(), instance.getId());
                }
                String poolKey = (instance.getPoolKey() == null || instance.getPoolKey().isBlank())
                        ? DEFAULT_POOL_KEY : instance.getPoolKey().trim();
                byPool.computeIfAbsent(poolKey, k -> new ArrayList<>()).add(instance);
            }

            log.info("刷新实例列表完成: 总实例数={}, 恢复状态={}, 新实例={}",
                    instances.size(), restoredCount, newInstanceCount);

            for (String poolKey : orderedPoolKeysList) {
                StrategyRuntime runtime = runtimeByPool.get(poolKey);
                if (runtime == null) {
                    continue;
                }
                List<ModelInstance> poolInstances = byPool.getOrDefault(poolKey, List.of());
                runtime.strategy.refreshInstances(poolInstances, settings.copy());
            }

            // 通知 InstanceWebClientManager 清理不再存在的实例连接池
            Set<Long> activeInstanceIds = instances.stream()
                    .map(ModelInstance::getId)
                    .filter(id -> id != null)
                    .collect(java.util.stream.Collectors.toSet());
            instanceWebClientManager.refreshInstances(activeInstanceIds);
        }

        /**
         * 快照所有实例的运行时状态。
         *
         * <p>遍歷所有策略運行時，收集每個實例的運行時狀態：
         * 請求計數、失敗計數、總延遲、最後使用時間、最後失敗時間、最後心跳時間、熔斷狀態等。
         */
        private Map<Long, RuntimeStateSnapshot> snapshotRuntimeStates() {
            Map<Long, RuntimeStateSnapshot> map = new ConcurrentHashMap<>();
            int snapshotCount = 0;
            for (StrategyRuntime runtime : runtimeByPool.values()) {
                if (runtime == null) {
                    continue;
                }
                for (ModelInstance instance : runtime.strategy.getInstances()) {
                    if (instance == null || instance.getId() == null) {
                        log.warn("发现无效实例: instance={}, id={}", instance, instance != null ? instance.getId() : null);
                        continue;
                    }
                    instance.ensureRuntimeState();
                    int requestCount = instance.getRequestCount() == null ? 0 : instance.getRequestCount().get();
                    int failureCount = instance.getFailureCount() == null ? 0 : instance.getFailureCount().get();
                    long totalLatency = instance.getTotalLatency() == null ? 0L : instance.getTotalLatency().get();

                    // 如果同一个 ID 已经存在快照，记录警告（理论上不应该发生）
                    if (map.containsKey(instance.getId())) {
                        log.warn("发现重复的实例 ID {}，覆盖之前的快照", instance.getId());
                    }

                    map.put(instance.getId(), new RuntimeStateSnapshot(
                            requestCount,
                            failureCount,
                            totalLatency,
                            instance.getLastUsedTime(),
                            instance.getLastFailureTime(),
                            instance.getLastHeartbeat(),
                            instance.isCircuitOpen()
                    ));
                    snapshotCount++;
                }
            }
            log.debug("快照运行时状态完成: 共 {} 个实例", snapshotCount);
            return map;
        }

        /**
         * 将快照状态应用到目标实例。
         *
         * <p>恢復實例的運行時狀態，包括原子計數器的值和時間戳。
         */
        private void applyRuntimeState(ModelInstance target, RuntimeStateSnapshot snapshot) {
            target.ensureRuntimeState();
            // 确保原子操作的正确性
            if (target.getRequestCount() != null) {
                target.getRequestCount().set(snapshot.requestCount());
            }
            if (target.getFailureCount() != null) {
                target.getFailureCount().set(snapshot.failureCount());
            }
            if (target.getTotalLatency() != null) {
                target.getTotalLatency().set(snapshot.totalLatency());
            }
            target.setLastUsedTime(snapshot.lastUsedTime());
            target.setLastFailureTime(snapshot.lastFailureTime());
            target.setLastHeartbeat(snapshot.lastHeartbeat());
            target.setCircuitOpen(snapshot.circuitOpen());
        }
    }

    /**
     * 策略指标记录
     * 
     * <p>包含策略运行时的拒绝统计，用于监控。
     */
    private record StrategyMetrics(long rejectBudgetCount, long rejectSamplingCount) {
        static StrategyMetrics empty() {
            return new StrategyMetrics(0L, 0L);
        }
    }

    /**
     * 策略获取结果记录
     */
    private record StrategyAcquire(ModelInstance instance) {}
    /**
     * 运行时状态快照记录
     * 
     * <p>用于在实例刷新时保存和恢复实例的运行时状态，避免状态丢失。
     */
    private record RuntimeStateSnapshot(int requestCount, int failureCount, long totalLatency,
                                        long lastUsedTime, long lastFailureTime, long lastHeartbeat,
                                        boolean circuitOpen) {}
}