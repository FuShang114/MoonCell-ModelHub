package com.mooncell.gateway.core.balancer;

import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadBalancer {
    private final ModelInstanceMapper modelMapper;
    private final Map<LoadBalancingAlgorithm, LoadBalancingStrategy> strategyCache = new ConcurrentHashMap<>();
    private volatile LoadBalancingSettings settings = LoadBalancingSettings.defaultSettings();
    private volatile LoadBalancingAlgorithm currentAlgorithm = LoadBalancingAlgorithm.TRADITIONAL;
    private volatile LoadBalancingStrategy activeStrategy;

    @PostConstruct
    public void init() {
        activeStrategy = createStrategy(settings.getAlgorithm());
        strategyCache.put(settings.getAlgorithm(), activeStrategy);
        activeStrategy.onActivate(settings.copy());
        refreshInstances();
    }

    public synchronized void updateSettings(LoadBalancingSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        int normalizedCore = Math.max(1, newSettings.getObjectPoolCoreSize());
        int normalizedMax = Math.max(normalizedCore, newSettings.getObjectPoolMaxSize());
        newSettings.setObjectPoolCoreSize(normalizedCore);
        newSettings.setObjectPoolMaxSize(normalizedMax);
        LoadBalancingAlgorithm newAlgorithm = newSettings.getAlgorithm() == null
                ? LoadBalancingAlgorithm.TRADITIONAL : newSettings.getAlgorithm();
        settings = newSettings.copy();
        if (newAlgorithm != currentAlgorithm) {
            log.info("Switch load balancing algorithm: {} -> {}", currentAlgorithm, newAlgorithm);
            activeStrategy.onDeactivate();
            activeStrategy = strategyCache.computeIfAbsent(newAlgorithm, this::createStrategy);
            currentAlgorithm = newAlgorithm;
            activeStrategy.onActivate(settings.copy());
            refreshInstances();
        } else {
            activeStrategy.onSettingsChanged(settings.copy());
        }
    }

    public LoadBalancingSettings getSettings() {
        LoadBalancingSettings copy = settings.copy();
        copy.setAlgorithm(currentAlgorithm);
        return copy;
    }

    public synchronized void refreshInstances() {
        List<ModelInstance> instances = modelMapper.findAll();
        if (instances == null) {
            instances = List.of();
        }
        activeStrategy.refreshInstances(instances, settings.copy());
    }

    public ModelInstance getNextAvailableInstance(int estimatedTokens) {
        return activeStrategy.acquire(Math.max(1, estimatedTokens));
    }

    public ModelInstance getNextAvailableInstance() {
        return getNextAvailableInstance(256);
    }

    public void releaseInstance(ModelInstance instance) {
        activeStrategy.release(instance);
    }

    public List<ModelInstance> getInstanceList() {
        return activeStrategy.getInstances();
    }

    public QueueStats getStats() {
        QueueStats stats = activeStrategy.getStats();
        stats.setAlgorithm(currentAlgorithm.name());
        return stats;
    }

    private LoadBalancingStrategy createStrategy(LoadBalancingAlgorithm algorithm) {
        return switch (algorithm) {
            case OBJECT_POOL -> new ObjectPoolStrategy();
            case TRADITIONAL -> new TraditionalStrategy();
        };
    }

    private interface LoadBalancingStrategy {
        void onActivate(LoadBalancingSettings settings);
        void onDeactivate();
        void onSettingsChanged(LoadBalancingSettings settings);
        void refreshInstances(List<ModelInstance> instances, LoadBalancingSettings settings);
        ModelInstance acquire(int estimatedTokens);
        void release(ModelInstance instance);
        List<ModelInstance> getInstances();
        QueueStats getStats();
    }

    private abstract static class BaseTokenStrategy implements LoadBalancingStrategy {
        protected final CopyOnWriteArrayList<InstanceWrapper> wrappers = new CopyOnWriteArrayList<>();
        protected final Map<String, InstanceWrapper> wrapperMap = new ConcurrentHashMap<>();
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
                wrapperMap.put(instance.getUrl(), wrapper);
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
    }

    private static class TraditionalStrategy extends BaseTokenStrategy {
        @Override
        public ModelInstance acquire(int estimatedTokens) {
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
            InstanceWrapper wrapper = wrapperMap.get(instance.getUrl());
            if (wrapper != null) {
                wrapper.release();
            }
        }
    }

    private static class ObjectPoolStrategy extends BaseTokenStrategy {
        @Override
        protected InstanceWrapper createWrapper(ModelInstance instance, LoadBalancingSettings settings) {
            return new InstanceWrapper(instance, settings.getObjectPoolCoreSize(), settings.getObjectPoolMaxSize());
        }

        @Override
        public void onActivate(LoadBalancingSettings settings) {
            super.onActivate(settings);
            for (InstanceWrapper wrapper : wrappers) {
                wrapper.reconfigurePool(settings.getObjectPoolCoreSize(), settings.getObjectPoolMaxSize());
            }
        }

        @Override
        public void onSettingsChanged(LoadBalancingSettings settings) {
            super.onSettingsChanged(settings);
            for (InstanceWrapper wrapper : wrappers) {
                wrapper.reconfigurePool(settings.getObjectPoolCoreSize(), settings.getObjectPoolMaxSize());
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
        public ModelInstance acquire(int estimatedTokens) {
            List<InstanceWrapper> samples = sampleWrappers();
            if (samples.isEmpty()) {
                return null;
            }
            samples.sort(Comparator.comparingDouble(wrapper -> wrapper.poolScore(estimatedTokens)));
            for (InstanceWrapper wrapper : samples) {
                if (wrapper.tryAcquireFromPool(estimatedTokens)) {
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
            InstanceWrapper wrapper = wrapperMap.get(instance.getUrl());
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
        private int poolCoreSize;
        private int poolMaxSize;
        private int poolAllocatedSize;
        private int poolActiveSize;
        private int poolIdleSize;

        InstanceWrapper(ModelInstance instance) {
            this.instance = instance;
            this.rpmTokens = instance.getEffectiveRpmLimit();
            this.tpmTokens = instance.getEffectiveTpmLimit();
        }

        InstanceWrapper(ModelInstance instance, int core, int max) {
            this(instance);
            reconfigurePool(core, max);
        }

        void reconfigurePool(int core, int max) {
            synchronized (tokenLock) {
                this.poolCoreSize = Math.max(1, core);
                this.poolMaxSize = Math.max(this.poolCoreSize, max);
                if (poolAllocatedSize < poolCoreSize) {
                    poolAllocatedSize = poolCoreSize;
                }
                if (poolAllocatedSize > poolMaxSize) {
                    poolAllocatedSize = Math.max(poolMaxSize, poolActiveSize);
                }
                poolIdleSize = Math.max(0, poolAllocatedSize - poolActiveSize);
            }
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

        boolean tryAcquireFromPool(int estimatedTokens) {
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
                } else if (poolAllocatedSize < poolMaxSize) {
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
                boolean poolCanGrow = poolIdleSize > 0 || poolAllocatedSize < poolMaxSize;
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
}