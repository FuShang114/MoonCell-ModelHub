package com.mooncell.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooncell.gateway.core.balancer.LoadBalancer;
import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.dto.FailureReasonStatDto;
import com.mooncell.gateway.dto.MetricPointDto;
import com.mooncell.gateway.dto.MonitorMetricsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.sun.management.OperatingSystemMXBean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonitoringMetricsService {
    private static final long SAMPLE_INTERVAL_MS = 5000L;
    private static final int MAX_POINTS = 120;
    private static final long FLUSH_INTERVAL_SECONDS = 30L; // 每30秒flush一次
    private static final String PERSISTENCE_DIR = "data/metrics";
    private static final String PERSISTENCE_FILE = "metrics-data.json";

    private final LoadBalancer loadBalancer;
    private final ObjectMapper objectMapper;

    // 使用 volatile 确保可见性，采样线程和读取线程之间的数据同步
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong throughputTokens = new AtomicLong(0);

    // 采样相关的状态变量，只在采样线程中修改，使用 volatile 确保读取线程可见性
    private volatile long lastSampleMs = System.currentTimeMillis();
    private volatile long lastGcCount = currentGcCount();
    private volatile long lastTotalRequests = 0;
    private volatile long lastSuccessRequests = 0;
    private volatile long lastFailedRequests = 0;
    private volatile long lastThroughputTokens = 0;

    // 当前指标值，使用 volatile 确保读取线程可见性
    private volatile double currentGcRatePerMin = 0.0d;
    private volatile double currentCpuUsage = 0.0d;
    private volatile double currentQps = 0.0d;
    private volatile double currentSuccessRate = 1.0d;
    private volatile double currentFailureRate = 0.0d;
    private volatile double currentThroughput = 0.0d;
    private volatile double currentResourceUsage = 0.0d;

    // 时间序列数据，使用同步的 ArrayDeque（只在采样线程中修改，读取时复制）
    private final ArrayDeque<MetricPointDto> gcRateSeries = new ArrayDeque<>();
    private final ArrayDeque<MetricPointDto> cpuUsageSeries = new ArrayDeque<>();
    private final ArrayDeque<MetricPointDto> qpsSeries = new ArrayDeque<>();
    private final ArrayDeque<MetricPointDto> successRateSeries = new ArrayDeque<>();
    private final ArrayDeque<MetricPointDto> failureRateSeries = new ArrayDeque<>();
    private final ArrayDeque<MetricPointDto> throughputSeries = new ArrayDeque<>();
    private final ArrayDeque<MetricPointDto> resourceUsageSeries = new ArrayDeque<>();

    /**
     * 按失败原因累计的总失败次数（所有历史累计）。
     * 使用 ConcurrentHashMap 实现无锁并发访问
     */
    private final ConcurrentHashMap<String, Long> failureReasonTotals = new ConcurrentHashMap<>();
    
    // 用于同步采样操作的锁（只在采样线程中使用，不影响请求路径）
    private final Object samplingLock = new Object();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        // 恢复持久化数据
        restoreFromDisk();
        
        // 启动异步采样任务
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-sampler");
            t.setDaemon(true);
            return t;
        });
        
        // 启动采样任务（每5秒采样一次）
        scheduler.scheduleAtFixedRate(this::asyncSample, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // 启动flush任务（每30秒flush一次）
        scheduler.scheduleAtFixedRate(this::asyncFlush, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        log.info("MonitoringMetricsService initialized with async sampling and persistence");
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // 关闭前最后一次flush
        asyncFlush();
    }

    /**
     * 记录请求开始（兼容占位）
     * <p>
     * 目前总请求数在最终判定成功/失败时再自增，保证 totalRequests = successRequests + failedRequests。
     * 该方法保留以兼容旧调用，但不再修改计数。
     */
    @Deprecated
    public void recordRequestStart() {
        // no-op
    }

    /**
     * 记录一次请求成功以及对应的 token 产出
     *
     * @param estimatedTokens 该请求估算产生的 token 数，允许为 0 或正数
     */
    public void recordRequestSuccess(int estimatedTokens) {
        // 为保证 totalRequests 与 successRequests/failedRequests 精确对应，
        // 总请求数在最终判定为「成功」时再进行 +1。
        totalRequests.incrementAndGet();
        successRequests.incrementAndGet();
        throughputTokens.addAndGet(Math.max(0, estimatedTokens));
    }

    /**
     * 记录一次失败请求（失败原因未知）
     */
    public void recordRequestFailure() {
        recordRequestFailure("UNKNOWN");
    }

    /**
     * 记录请求失败（无锁实现，使用 ConcurrentHashMap）
     */
    public void recordRequestFailure(String reasonKey) {
        // 同样在最终判定为「失败」时对总请求数 +1，保证：
        // totalRequests = successRequests + failedRequests
        totalRequests.incrementAndGet();
        failedRequests.incrementAndGet();
        if (reasonKey == null || reasonKey.isBlank()) {
            reasonKey = "UNKNOWN";
        }
        failureReasonTotals.merge(reasonKey, 1L, Long::sum);
    }

    /**
     * 获取监控指标（无锁读取，使用 volatile 变量确保可见性）
     */
    public MonitorMetricsDto getMonitorMetrics() {
        MonitorMetricsDto dto = new MonitorMetricsDto();
        
        // 读取 volatile 变量（无锁，高性能）
        dto.setGcRatePerMin(currentGcRatePerMin);
        dto.setCpuUsage(currentCpuUsage);
        dto.setQps(currentQps);
        dto.setSuccessRate(currentSuccessRate);
        dto.setFailureRate(currentFailureRate);
        dto.setThroughput(currentThroughput);
        dto.setResourceUsage(currentResourceUsage);

        // 复制时间序列数据（快速复制，避免长时间持有锁）
        synchronized (samplingLock) {
            dto.setGcRateSeries(new ArrayList<>(gcRateSeries));
            dto.setCpuUsageSeries(new ArrayList<>(cpuUsageSeries));
            dto.setQpsSeries(new ArrayList<>(qpsSeries));
            dto.setSuccessRateSeries(new ArrayList<>(successRateSeries));
            dto.setFailureRateSeries(new ArrayList<>(failureRateSeries));
            dto.setThroughputSeries(new ArrayList<>(throughputSeries));
            dto.setResourceUsageSeries(new ArrayList<>(resourceUsageSeries));
        }
        
        // 失败原因统计：使用 ConcurrentHashMap 的无锁迭代
        List<FailureReasonStatDto> allFailureStats = new ArrayList<>();
        long totalFailed = failedRequests.get();
        for (Map.Entry<String, Long> entry : failureReasonTotals.entrySet()) {
            String key = entry.getKey();
            long count = entry.getValue();
            double ratio = totalFailed > 0 ? (double) count / (double) totalFailed : 0.0d;
            String display = formatFailureReason(key);
            allFailureStats.add(new FailureReasonStatDto(key, display, count, ratio));
        }
        dto.setFailureReasons(allFailureStats);
        
        return dto;
    }

    /**
     * 异步采样方法，在后台线程执行
     */
    private void asyncSample() {
        try {
            long now = System.currentTimeMillis();
            synchronized (samplingLock) {
                maybeSample(now);
            }
        } catch (Exception e) {
            log.error("Error in async sampling", e);
        }
    }

    /**
     * 异步 flush 方法，在后台线程执行
     */
    private void asyncFlush() {
        try {
            flushToDisk();
        } catch (Exception e) {
            log.error("Error flushing metrics to disk", e);
        }
    }

    private void maybeSample(long now) {
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) {
            return;
        }
        double intervalSec = Math.max(1.0d, (now - lastSampleMs) / 1000.0d);
        long total = totalRequests.get();
        long success = successRequests.get();
        long failure = failedRequests.get();
        long tokens = throughputTokens.get();

        long deltaTotal = Math.max(0, total - lastTotalRequests);
        long deltaSuccess = Math.max(0, success - lastSuccessRequests);
        long deltaFailure = Math.max(0, failure - lastFailedRequests);
        long deltaTokens = Math.max(0, tokens - lastThroughputTokens);

        currentQps = deltaTotal / intervalSec;
        currentThroughput = deltaTokens / intervalSec;
        if (deltaTotal > 0) {
            currentSuccessRate = (double) deltaSuccess / (double) deltaTotal;
            currentFailureRate = (double) deltaFailure / (double) deltaTotal;
        } else {
            currentSuccessRate = 1.0d;
            currentFailureRate = 0.0d;
        }

        long gcCountNow = currentGcCount();
        long gcDelta = Math.max(0, gcCountNow - lastGcCount);
        currentGcRatePerMin = gcDelta * (60.0d / intervalSec);
        currentCpuUsage = currentCpuUsageRate();
        currentResourceUsage = currentResourceUsageRate();

        addPoint(gcRateSeries, now, currentGcRatePerMin);
        addPoint(cpuUsageSeries, now, currentCpuUsage);
        addPoint(qpsSeries, now, currentQps);
        addPoint(successRateSeries, now, currentSuccessRate);
        addPoint(failureRateSeries, now, currentFailureRate);
        addPoint(throughputSeries, now, currentThroughput);
        addPoint(resourceUsageSeries, now, currentResourceUsage);

        lastSampleMs = now;
        lastGcCount = gcCountNow;
        lastTotalRequests = total;
        lastSuccessRequests = success;
        lastFailedRequests = failure;
        lastThroughputTokens = tokens;
    }

    private long currentGcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gcBean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private double currentCpuUsageRate() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        double value = -1.0d;
        if (bean instanceof OperatingSystemMXBean osBean) {
            value = osBean.getProcessCpuLoad();
            if (value < 0.0d || value > 1.0d) {
                value = osBean.getCpuLoad();
            }
        }
        if (value < 0.0d || value > 1.0d) {
            double loadAvg = bean.getSystemLoadAverage();
            int cpuCount = Math.max(1, bean.getAvailableProcessors());
            if (loadAvg >= 0) {
                value = Math.min(1.0d, loadAvg / cpuCount);
            } else {
                value = 0.0d;
            }
        }
        return clamp01(value);
    }

    /**
     * 计算资源使用率（在采样线程中调用，避免阻塞请求路径）
     * 注意：这个方法在采样线程中执行，不会影响请求路径性能
     */
    private double currentResourceUsageRate() {
        try {
            LoadBalancer.QueueStats stats = loadBalancer.getStats();
            List<ModelInstance> instances = loadBalancer.getInstanceList();
            int totalRpm = 0;
            int totalTpm = 0;
            for (ModelInstance instance : instances) {
                if (!Boolean.TRUE.equals(instance.getIsActive())) {
                    continue;
                }
                totalRpm += Math.max(0, instance.getEffectiveRpmLimit());
                totalTpm += Math.max(0, instance.getEffectiveTpmLimit());
            }
            double rpmUsage = totalRpm <= 0 ? 0.0d : 1.0d - ((double) stats.getAvailableRpm() / (double) totalRpm);
            double tpmUsage = totalTpm <= 0 ? 0.0d : 1.0d - ((double) stats.getAvailableTpm() / (double) totalTpm);
            return clamp01(Math.max(rpmUsage, tpmUsage));
        } catch (Exception e) {
            // 采样失败不应该影响系统运行，返回默认值
            log.debug("Failed to calculate resource usage rate", e);
            return 0.0d;
        }
    }

    private String formatFailureReason(String reasonKey) {
        if (reasonKey == null || reasonKey.isBlank()) {
            return "未知原因";
        }
        return switch (reasonKey) {
            case "BAD_REQUEST" -> "请求参数错误";
            case "DUPLICATE_REQUEST" -> "重复请求（幂等控制）";
            case "NO_INSTANCE_OR_RATE_LIMIT" -> "无可用实例或触发限流";
            case "DOWNSTREAM_ERROR" -> "下游模型接口错误";
            case "CLIENT_CANCELLED" -> "客户端取消（超时/中断）";
            default -> reasonKey;
        };
    }

    private void addPoint(ArrayDeque<MetricPointDto> series, long ts, double value) {
        series.addLast(new MetricPointDto(ts, value));
        while (series.size() > MAX_POINTS) {
            series.removeFirst();
        }
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    /**
     * 持久化数据结构
     */
    private static class PersistedData {
        public long totalRequests;
        public long successRequests;
        public long failedRequests;
        public long throughputTokens;
        public Map<String, Long> failureReasonTotals = new HashMap<>();
    }

    /**
     * 将数据flush到磁盘
     */
    private void flushToDisk() {
        try {
            Path dir = Paths.get(PERSISTENCE_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            
            PersistedData data = new PersistedData();
            // 无锁读取 AtomicLong 和 ConcurrentHashMap
            data.totalRequests = totalRequests.get();
            data.successRequests = successRequests.get();
            data.failedRequests = failedRequests.get();
            data.throughputTokens = throughputTokens.get();
            data.failureReasonTotals = new HashMap<>(failureReasonTotals);
            
            Path file = dir.resolve(PERSISTENCE_FILE);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            log.debug("Metrics data flushed to disk");
        } catch (IOException e) {
            log.error("Failed to flush metrics data to disk", e);
        }
    }

    /**
     * 从磁盘恢复数据
     */
    private void restoreFromDisk() {
        try {
            Path file = Paths.get(PERSISTENCE_DIR, PERSISTENCE_FILE);
            if (!Files.exists(file)) {
                log.info("No persisted metrics data found, starting with default values");
                return;
            }
            
            PersistedData data = objectMapper.readValue(file.toFile(), PersistedData.class);
            
            // 无锁设置 AtomicLong 和 ConcurrentHashMap
            totalRequests.set(data.totalRequests);
            successRequests.set(data.successRequests);
            failedRequests.set(data.failedRequests);
            throughputTokens.set(data.throughputTokens);
            failureReasonTotals.clear();
            if (data.failureReasonTotals != null) {
                failureReasonTotals.putAll(data.failureReasonTotals);
            }
            
            log.info("Metrics data restored from disk: totalRequests={}, successRequests={}, failedRequests={}, failureReasons={}",
                    data.totalRequests, data.successRequests, data.failedRequests, data.failureReasonTotals.size());
        } catch (IOException e) {
            log.warn("Failed to restore metrics data from disk, starting with default values", e);
        }
    }

    /**
     * 清零所有统计数据（需要同步操作，因为涉及多个数据结构）
     */
    public void resetMetrics() {
        // 无锁重置 AtomicLong 和 ConcurrentHashMap
        totalRequests.set(0);
        successRequests.set(0);
        failedRequests.set(0);
        throughputTokens.set(0);
        failureReasonTotals.clear();
        
        // 同步重置时间序列数据（避免并发修改）
        synchronized (samplingLock) {
            gcRateSeries.clear();
            cpuUsageSeries.clear();
            qpsSeries.clear();
            successRateSeries.clear();
            failureRateSeries.clear();
            throughputSeries.clear();
            resourceUsageSeries.clear();
            
            lastSampleMs = System.currentTimeMillis();
            lastGcCount = currentGcCount();
            lastTotalRequests = 0;
            lastSuccessRequests = 0;
            lastFailedRequests = 0;
            lastThroughputTokens = 0;
            
            currentGcRatePerMin = 0.0d;
            currentCpuUsage = 0.0d;
            currentQps = 0.0d;
            currentSuccessRate = 1.0d;
            currentFailureRate = 0.0d;
            currentThroughput = 0.0d;
            currentResourceUsage = 0.0d;
        }
        
        // 立即flush清零后的数据
        asyncFlush();
        log.info("All metrics data has been reset");
    }
}
