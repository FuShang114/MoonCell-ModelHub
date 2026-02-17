package com.mooncell.gateway.core.balancer;

import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.dto.StrategyStatusDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadBalancer {
    private final ModelInstanceMapper modelMapper;
    private final AtomicLong runtimeSeq = new AtomicLong(0);
    private final AtomicLong leaseSeq = new AtomicLong(0);
    private final CopyOnWriteArrayList<StrategyRuntime> runtimes = new CopyOnWriteArrayList<>();
    private final Map<String, LeaseContext> activeLeases = new ConcurrentHashMap<>();
    private final ArrayDeque<Integer> tokenHistogram = new ArrayDeque<>();
    private final Object histogramLock = new Object();
    private volatile int shortBoundaryTokens = 512;
    private volatile int mediumBoundaryTokens = 2048;
    private volatile long lastBoundaryUpdateMs = 0L;
    private volatile LoadBalancingSettings settings = LoadBalancingSettings.defaultSettings();
    private volatile StrategyRuntime activeRuntime;

    @PostConstruct
    public void init() {
        StrategyRuntime runtime = createRuntime(settings.getAlgorithm(), settings.copy());
        runtime.state = RuntimeState.ACTIVE;
        runtime.strategy.onActivate(settings.copy());
        activeRuntime = runtime;
        runtimes.add(runtime);
        refreshInstances();
    }

    public synchronized void updateSettings(LoadBalancingSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        LoadBalancingAlgorithm newAlgorithm = newSettings.getAlgorithm() == null
                ? LoadBalancingAlgorithm.TRADITIONAL : newSettings.getAlgorithm();
        settings = newSettings.copy();

        StrategyRuntime current = activeRuntime;
        if (current == null) {
            StrategyRuntime created = createRuntime(newAlgorithm, settings.copy());
            created.state = RuntimeState.ACTIVE;
            created.strategy.onActivate(settings.copy());
            activeRuntime = created;
            runtimes.add(created);
            refreshInstances();
            return;
        }

        if (newAlgorithm != current.algorithm) {
            log.info("Smooth switch load balancing algorithm: {} -> {}", current.algorithm, newAlgorithm);
            current.state = RuntimeState.DRAINING;
            StrategyRuntime next = createRuntime(newAlgorithm, settings.copy());
            next.state = RuntimeState.ACTIVE;
            next.strategy.onActivate(settings.copy());
            activeRuntime = next;
            runtimes.add(next);
            refreshInstances();
            cleanupDrainingRuntimes();
        } else {
            current.settings = settings.copy();
            current.strategy.onSettingsChanged(settings.copy());
        }
    }

    public LoadBalancingSettings getSettings() {
        LoadBalancingSettings copy = settings.copy();
        StrategyRuntime runtime = activeRuntime;
        copy.setAlgorithm(runtime == null ? LoadBalancingAlgorithm.TRADITIONAL : runtime.algorithm);
        return copy;
    }

    public synchronized void refreshInstances() {
        List<ModelInstance> instances = modelMapper.findAll();
        if (instances == null) {
            instances = List.of();
        }
        StrategyRuntime runtime = activeRuntime;
        if (runtime != null) {
            runtime.strategy.refreshInstances(instances, settings.copy());
        }
    }

    public InstanceLease acquireLease(int estimatedTokens) {
        StrategyRuntime runtime = activeRuntime;
        if (runtime == null || runtime.state != RuntimeState.ACTIVE) {
            return null;
        }
        int tokens = Math.max(1, estimatedTokens);
        RequestBucket bucket = resolveBucket(tokens);
        maybeUpdateDynamicBoundaries(tokens);
        ModelInstance instance = runtime.strategy.acquire(tokens, bucket);
        if (instance == null) {
            return null;
        }
        String leaseId = "lease-" + leaseSeq.incrementAndGet() + "-" + System.nanoTime();
        runtime.inflightLeases.incrementAndGet();
        runtime.inflightByBucket.get(bucket).incrementAndGet();
        activeLeases.put(leaseId, new LeaseContext(runtime, instance, bucket));
        return new InstanceLease(leaseId, instance, tokens, bucket.name());
    }

    public void releaseLease(String leaseId) {
        if (leaseId == null || leaseId.isBlank()) {
            return;
        }
        LeaseContext context = activeLeases.remove(leaseId);
        if (context == null) {
            return;
        }
        context.runtime.strategy.release(context.instance);
        context.runtime.inflightLeases.decrementAndGet();
        context.runtime.inflightByBucket.get(context.bucket).decrementAndGet();
        cleanupDrainingRuntimes();
    }

    public ModelInstance getNextAvailableInstance(int estimatedTokens) {
        InstanceLease lease = acquireLease(estimatedTokens);
        return lease == null ? null : lease.getInstance();
    }

    public ModelInstance getNextAvailableInstance() {
        return getNextAvailableInstance(256);
    }

    public void releaseInstance(ModelInstance instance) {
        if (instance == null) {
            return;
        }
        Long id = instance.getId();
        StrategyRuntime runtime = activeRuntime;
        if (runtime != null && runtime.strategy.releaseById(id)) {
            return;
        }
        for (StrategyRuntime dr : runtimes) {
            if (dr != runtime && dr.state == RuntimeState.DRAINING && dr.strategy.releaseById(id)) {
                return;
            }
        }
    }

    public List<ModelInstance> getInstanceList() {
        StrategyRuntime runtime = activeRuntime;
        return runtime == null ? List.of() : runtime.strategy.getInstances();
    }

    public QueueStats getStats() {
        StrategyRuntime runtime = activeRuntime;
        if (runtime == null) {
            QueueStats empty = new QueueStats(0, 0, 0, 0, System.currentTimeMillis());
            empty.setAlgorithm(LoadBalancingAlgorithm.TRADITIONAL.name());
            return empty;
        }
        QueueStats stats = runtime.strategy.getStats();
        stats.setAlgorithm(runtime.algorithm.name());
        return stats;
    }

    public List<StrategyStatusDto> getStrategyStatuses() {
        List<StrategyStatusDto> result = new ArrayList<>();
        for (StrategyRuntime runtime : runtimes) {
            StrategyStatusDto dto = new StrategyStatusDto();
            dto.setRuntimeId(runtime.runtimeId);
            dto.setAlgorithm(runtime.algorithm.name());
            dto.setState(runtime.state.name());
            dto.setInflightLeases(runtime.inflightLeases.get());
            dto.setSinceEpochMs(runtime.activatedAtMs);
            dto.setShortBoundaryTokens(shortBoundaryTokens);
            dto.setMediumBoundaryTokens(mediumBoundaryTokens);
            dto.setShortWeight(settings.getShortBucketWeight());
            dto.setMediumWeight(settings.getMediumBucketWeight());
            dto.setLongWeight(settings.getLongBucketWeight());
            result.add(dto);
        }
        return result;
    }

    private void cleanupDrainingRuntimes() {
        for (StrategyRuntime runtime : new ArrayList<>(runtimes)) {
            if (runtime.state != RuntimeState.DRAINING) {
                continue;
            }
            if (runtime.inflightLeases.get() > 0) {
                continue;
            }
            runtime.strategy.onDeactivate();
            runtime.state = RuntimeState.RETIRED;
            runtimes.remove(runtime);
        }
    }

    private StrategyRuntime createRuntime(LoadBalancingAlgorithm algorithm, LoadBalancingSettings runtimeSettings) {
        LoadBalancingStrategy strategy = switch (algorithm) {
            case OBJECT_POOL -> new ObjectPoolStrategy();
            case TRADITIONAL -> new TraditionalStrategy();
        };
        return new StrategyRuntime(
                "rt-" + runtimeSeq.incrementAndGet(),
                algorithm,
                runtimeSettings.copy(),
                strategy
        );
    }

    private RequestBucket resolveBucket(int estimatedTokens) {
        if (estimatedTokens <= shortBoundaryTokens) {
            return RequestBucket.SHORT;
        }
        if (estimatedTokens <= mediumBoundaryTokens) {
            return RequestBucket.MEDIUM;
        }
        return RequestBucket.LONG;
    }

    private void maybeUpdateDynamicBoundaries(int estimatedTokens) {
        synchronized (histogramLock) {
            tokenHistogram.addLast(Math.max(1, estimatedTokens));
            while (tokenHistogram.size() > settings.getHistogramSampleSize()) {
                tokenHistogram.removeFirst();
            }
            if (!settings.isDynamicBucketingEnabled()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastBoundaryUpdateMs < settings.getBucketUpdateIntervalSeconds() * 1000L) {
                return;
            }
            lastBoundaryUpdateMs = now;
            if (tokenHistogram.size() < 32) {
                return;
            }
            List<Integer> sorted = new ArrayList<>(tokenHistogram);
            sorted.sort(Integer::compareTo);
            int p40 = sorted.get((int) Math.floor((sorted.size() - 1) * 0.40d));
            int p80 = sorted.get((int) Math.floor((sorted.size() - 1) * 0.80d));
            shortBoundaryTokens = Math.max(64, p40);
            mediumBoundaryTokens = Math.max(shortBoundaryTokens + 64, p80);
        }
    }

    private interface LoadBalancingStrategy {
        void onActivate(LoadBalancingSettings settings);
        void onDeactivate();
        void onSettingsChanged(LoadBalancingSettings settings);
        void refreshInstances(List<ModelInstance> instances, LoadBalancingSettings settings);
        ModelInstance acquire(int estimatedTokens, RequestBucket bucket);
        void release(ModelInstance instance);
        boolean releaseById(Long instanceId);
        List<ModelInstance> getInstances();
        QueueStats getStats();
    }

    private abstract static class BaseTokenStrategy implements LoadBalancingStrategy {
        protected final CopyOnWriteArrayList<InstanceWrapper> wrappers = new CopyOnWriteArrayList<>();
        protected final Map<Long, InstanceWrapper> wrapperMap = new ConcurrentHashMap<>();
        protected volatile LoadBalancingSettings settings = LoadBalancingSettings.defaultSettings();

        @Override
        public void onActivate(LoadBalancingSettings settings) {
            this.settings = settings.copy();
        }

        @Override
        public void onDeactivate() {
            wrappers.clear();
            wrapperMap.clear();
        }

        @Override
        public void onSettingsChanged(LoadBalancingSettings settings) {
            this.settings = settings.copy();
        }

        @Override
        public void refreshInstances(List<ModelInstance> instances, LoadBalancingSettings settings) {
            this.settings = settings.copy();
            wrappers.clear();
            wrapperMap.clear();
            for (ModelInstance instance : instances) {
                InstanceWrapper wrapper = createWrapper(instance, this.settings);
                wrappers.add(wrapper);
                if (instance.getId() != null) {
                    wrapperMap.put(instance.getId(), wrapper);
                }
            }
        }

        protected InstanceWrapper createWrapper(ModelInstance instance, LoadBalancingSettings settings) {
            return new InstanceWrapper(instance);
        }

        protected int sampleCount() {
            return Math.max(1, settings.getSampleCount());
        }

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
        public boolean releaseById(Long instanceId) {
            if (instanceId == null) {
                return false;
            }
            InstanceWrapper wrapper = wrapperMap.get(instanceId);
            if (wrapper == null) {
                return false;
            }
            wrapper.release();
            return true;
        }
    }

    private static class TraditionalStrategy extends BaseTokenStrategy {
        @Override
        public ModelInstance acquire(int estimatedTokens, RequestBucket bucket) {
            List<InstanceWrapper> samples = sampleWrappers();
            if (samples.isEmpty()) {
                return null;
            }
            samples.sort(Comparator.comparingDouble(wrapper -> wrapper.traditionalScore(estimatedTokens)));
            for (InstanceWrapper wrapper : samples) {
                if (wrapper.tryAcquire(estimatedTokens)) {
                    return wrapper.instance();
                }
            }
            return null;
        }

        @Override
        public void release(ModelInstance instance) {
            if (instance == null) {
                return;
            }
            InstanceWrapper wrapper = wrapperMap.get(instance.getId());
            if (wrapper != null) {
                wrapper.release();
            }
        }
    }

    private static class ObjectPoolStrategy extends BaseTokenStrategy {
        @Override
        protected InstanceWrapper createWrapper(ModelInstance instance, LoadBalancingSettings settings) {
            InstanceWrapper wrapper = new InstanceWrapper(instance);
            wrapper.applyBucketWeights(settings.getShortBucketWeight(),
                    settings.getMediumBucketWeight(), settings.getLongBucketWeight());
            return wrapper;
        }

        @Override
        public void onSettingsChanged(LoadBalancingSettings settings) {
            super.onSettingsChanged(settings);
            for (InstanceWrapper wrapper : wrappers) {
                wrapper.applyBucketWeights(settings.getShortBucketWeight(),
                        settings.getMediumBucketWeight(), settings.getLongBucketWeight());
            }
        }

        @Override
        public void onDeactivate() {
            for (InstanceWrapper wrapper : wrappers) {
                wrapper.destroyPool();
            }
            super.onDeactivate();
        }

        @Override
        public ModelInstance acquire(int estimatedTokens, RequestBucket bucket) {
            List<InstanceWrapper> samples = sampleWrappers();
            if (samples.isEmpty()) {
                return null;
            }
            List<InstanceWrapper> shuffled = new ArrayList<>(samples);
            for (int i = shuffled.size() - 1; i > 0; i--) {
                int j = ThreadLocalRandom.current().nextInt(i + 1);
                InstanceWrapper tmp = shuffled.get(i);
                shuffled.set(i, shuffled.get(j));
                shuffled.set(j, tmp);
            }
            for (InstanceWrapper wrapper : shuffled) {
                if (wrapper.tryAcquireFromPool(estimatedTokens, true)) {
                    return wrapper.instance();
                }
            }
            for (InstanceWrapper wrapper : shuffled) {
                if (wrapper.tryAcquireFromPool(estimatedTokens, false)) {
                    return wrapper.instance();
                }
            }
            return null;
        }

        @Override
        public void release(ModelInstance instance) {
            if (instance == null) {
                return;
            }
            InstanceWrapper wrapper = wrapperMap.get(instance.getId());
            if (wrapper != null) {
                wrapper.releaseToPool();
            }
        }
    }

    private static class InstanceWrapper {
        private final ModelInstance instance;
        private final AtomicInteger currentConcurrency = new AtomicInteger(0);
        private final Object tokenLock = new Object();
        private double rpmTokens;
        private double tpmTokens;
        private long lastRefillNanos = System.nanoTime();
        private int dynamicPoolCap;
        private int poolAllocatedSize;
        private int poolActiveSize;
        private int poolIdleSize;

        InstanceWrapper(ModelInstance instance) {
            this.instance = instance;
            this.rpmTokens = instance.getEffectiveRpmLimit();
            this.tpmTokens = instance.getEffectiveTpmLimit();
            this.dynamicPoolCap = basePoolCap(instance.getEffectiveRpmLimit(), instance.getEffectiveTpmLimit());
            this.poolAllocatedSize = Math.max(1, dynamicPoolCap / 2);
            this.poolIdleSize = poolAllocatedSize;
        }

        void destroyPool() {
            synchronized (tokenLock) {
                poolAllocatedSize = 0;
                poolActiveSize = 0;
                poolIdleSize = 0;
            }
        }

        boolean tryAcquire(int estimatedTokens) {
            synchronized (tokenLock) {
                if (!isHealthy()) {
                    return false;
                }
                refillTokens();
                if (rpmTokens < 1.0d || tpmTokens < estimatedTokens) {
                    return false;
                }
                rpmTokens -= 1.0d;
                tpmTokens -= estimatedTokens;
                currentConcurrency.incrementAndGet();
                return true;
            }
        }

        boolean tryAcquireFromPool(int estimatedTokens, boolean idleOnly) {
            synchronized (tokenLock) {
                if (!isHealthy()) {
                    return false;
                }
                refillTokens();
                if (rpmTokens < 1.0d || tpmTokens < estimatedTokens) {
                    return false;
                }
                if (poolIdleSize > 0) {
                    poolIdleSize--;
                    poolActiveSize++;
                } else if (!idleOnly && poolAllocatedSize < dynamicPoolCap) {
                    poolAllocatedSize++;
                    poolActiveSize++;
                } else {
                    return false;
                }
                rpmTokens -= 1.0d;
                tpmTokens -= estimatedTokens;
                currentConcurrency.incrementAndGet();
                return true;
            }
        }

        void release() {
            synchronized (tokenLock) {
                if (currentConcurrency.get() > 0) {
                    currentConcurrency.decrementAndGet();
                }
            }
        }

        void releaseToPool() {
            synchronized (tokenLock) {
                if (currentConcurrency.get() > 0) {
                    currentConcurrency.decrementAndGet();
                }
                if (poolActiveSize > 0) {
                    poolActiveSize--;
                    poolIdleSize = Math.min(poolAllocatedSize, poolIdleSize + 1);
                }
            }
        }

        double traditionalScore(int estimatedTokens) {
            synchronized (tokenLock) {
                refillTokens();
                if (!canAcquireWithBudgetAndConcurrency(estimatedTokens)) {
                    return Double.MAX_VALUE;
                }
                double concurrencyPressure = normalizeInflightPressure();
                double rpmPressure = 1.0d - tokenHeadroom(rpmTokens, instance.getEffectiveRpmLimit());
                double tpmPressure = 1.0d - tokenHeadroom(tpmTokens, instance.getEffectiveTpmLimit());
                return 0.60d * concurrencyPressure + 0.20d * rpmPressure + 0.20d * tpmPressure;
            }
        }

        double poolScore(int estimatedTokens) {
            synchronized (tokenLock) {
                refillTokens();
                if (!canAcquireWithBudgetAndConcurrency(estimatedTokens)) {
                    return Double.MAX_VALUE;
                }
                boolean poolCanGrow = poolIdleSize > 0 || poolAllocatedSize < dynamicPoolCap;
                if (!poolCanGrow) {
                    return Double.MAX_VALUE;
                }
                double poolPressure = poolAllocatedSize <= 0 ? 0.0d : (double) poolActiveSize / poolAllocatedSize;
                double concurrencyPressure = normalizeInflightPressure();
                double rpmPressure = 1.0d - tokenHeadroom(rpmTokens, instance.getEffectiveRpmLimit());
                double tpmPressure = 1.0d - tokenHeadroom(tpmTokens, instance.getEffectiveTpmLimit());
                return 0.45d * poolPressure + 0.35d * concurrencyPressure + 0.10d * rpmPressure + 0.10d * tpmPressure;
            }
        }

        private boolean canAcquireWithBudgetAndConcurrency(int estimatedTokens) {
            if (!isHealthy()) {
                return false;
            }
            return rpmTokens >= 1.0d && tpmTokens >= estimatedTokens;
        }

        private double normalizeInflightPressure() {
            int inflight = currentConcurrency.get();
            return inflight <= 0 ? 0.0d : inflight / (inflight + 8.0d);
        }

        private double tokenHeadroom(double tokens, int limit) {
            if (limit <= 0) {
                return 0.0d;
            }
            return Math.min(1.0d, Math.max(0.0d, tokens / limit));
        }

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

        int currentConcurrency() {
            return currentConcurrency.get();
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

        void applyBucketWeights(int shortWeight, int mediumWeight, int longWeight) {
            synchronized (tokenLock) {
                int totalWeight = Math.max(1, shortWeight + mediumWeight + longWeight);
                int weightedCap = Math.max(4, (instance.getEffectiveRpmLimit() / 60) * totalWeight / 100);
                this.dynamicPoolCap = Math.max(4, Math.min(64, weightedCap));
                if (poolAllocatedSize > dynamicPoolCap) {
                    poolAllocatedSize = Math.max(poolActiveSize, dynamicPoolCap);
                }
                if (poolAllocatedSize <= 0) {
                    poolAllocatedSize = Math.max(1, dynamicPoolCap / 2);
                }
                poolIdleSize = Math.max(0, poolAllocatedSize - poolActiveSize);
            }
        }

        private int basePoolCap(int rpmLimit, int tpmLimit) {
            int rpmBase = Math.max(4, rpmLimit / 1000);
            int tpmBase = Math.max(4, tpmLimit / 200000);
            return Math.max(4, Math.min(64, Math.max(rpmBase, tpmBase)));
        }

        ModelInstance instance() {
            return instance;
        }
    }

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

    public static class InstanceLease {
        private final String leaseId;
        private final ModelInstance instance;
        private final int estimatedTokens;
        private final String bucket;

        public InstanceLease(String leaseId, ModelInstance instance, int estimatedTokens, String bucket) {
            this.leaseId = leaseId;
            this.instance = instance;
            this.estimatedTokens = estimatedTokens;
            this.bucket = bucket;
        }

        public String getLeaseId() { return leaseId; }
        public ModelInstance getInstance() { return instance; }
        public int getEstimatedTokens() { return estimatedTokens; }
        public String getBucket() { return bucket; }
    }

    private enum RequestBucket {
        SHORT,
        MEDIUM,
        LONG
    }

    private enum RuntimeState {
        ACTIVE,
        DRAINING,
        RETIRED
    }

    private static class StrategyRuntime {
        private final String runtimeId;
        private final LoadBalancingAlgorithm algorithm;
        private volatile LoadBalancingSettings settings;
        private final LoadBalancingStrategy strategy;
        private volatile RuntimeState state;
        private final long activatedAtMs;
        private final AtomicInteger inflightLeases = new AtomicInteger(0);
        private final EnumMap<RequestBucket, AtomicInteger> inflightByBucket = new EnumMap<>(RequestBucket.class);

        StrategyRuntime(String runtimeId, LoadBalancingAlgorithm algorithm, LoadBalancingSettings settings,
                        LoadBalancingStrategy strategy) {
            this.runtimeId = runtimeId;
            this.algorithm = algorithm;
            this.settings = settings;
            this.strategy = strategy;
            this.state = RuntimeState.ACTIVE;
            this.activatedAtMs = System.currentTimeMillis();
            for (RequestBucket bucket : RequestBucket.values()) {
                inflightByBucket.put(bucket, new AtomicInteger(0));
            }
        }
    }

    private record LeaseContext(StrategyRuntime runtime, ModelInstance instance, RequestBucket bucket) {}
}