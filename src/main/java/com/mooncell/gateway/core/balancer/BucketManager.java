package com.mooncell.gateway.core.balancer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 分桶與直方圖管理組件
 *
 * <p>負責：
 * <ul>
 *   <li>根據配置解析並維護分桶邊界與權重</li>
 *   <li>維護 Token 直方圖，用於動態調整分桶邊界</li>
 *   <li>計算自適應分桶邊界刷新間隔</li>
 * </ul>
 *
 * <p>該類不依賴具體策略實現，只暴露整數桶索引與邊界/權重結果，
 * 由 {@link LoadBalancer} 負責將桶索引映射到 {@code RequestBucket}。
 */
final class BucketManager {

    /** Token 數量直方圖，用於動態調整分桶邊界 */
    private final ArrayDeque<Integer> tokenHistogram = new ArrayDeque<>();
    /** 直方圖操作同步鎖 */
    private final Object histogramLock = new Object();
    /** 當前活躍的分桶邊界值列表（Token 上限） */
    private volatile List<Integer> activeBucketRanges = List.of(1024, 2048, 4096, 8192, 16384);
    /** 各分桶的權重列表，用於對象池策略的資源分配 */
    private volatile List<Integer> activeBucketWeights = List.of(30, 25, 20, 15, 10);
    /** 上次更新分桶邊界的時間戳（毫秒） */
    private volatile long lastBoundaryUpdateMs = 0L;
    /** 上次觀察到的分桶分佈（用於自適應更新間隔計算） */
    private volatile double[] lastObservedBucketDist = null;

    List<Integer> getActiveBucketRanges() {
        return activeBucketRanges;
    }

    List<Integer> getActiveBucketWeights() {
        return activeBucketWeights;
    }

    /**
     * 根據當前配置初始化分桶邊界與權重。
     */
    void initFromSettings(LoadBalancingSettings cfg) {
        int count = Math.max(5, Math.min(6, cfg.getBucketCount()));
        this.activeBucketRanges = parseBucketRanges(cfg, count);
        this.activeBucketWeights = parseBucketWeights(cfg, count);
    }

    /**
     * 在配置變更時更新分桶邊界與權重。
     */
    void updateFromSettings(LoadBalancingSettings cfg) {
        initFromSettings(cfg);
    }

    /**
     * 根據 Token 數量解析所屬分桶索引。
     *
     * @param estimatedTokens 預估 Token 數量
     * @return 分桶索引（從 0 開始），永遠在當前桶數範圍內
     */
    int resolveBucketIndex(int estimatedTokens) {
        List<Integer> ranges = this.activeBucketRanges;
        int bucketCount = Math.max(1, ranges.size());
        int tokens = Math.max(1, estimatedTokens);
        for (int i = 0; i < bucketCount; i++) {
            if (tokens <= ranges.get(i)) {
                return i;
            }
        }
        return bucketCount - 1;
    }

    /**
     * 記錄一次請求的 Token，並在需要時嘗試動態更新分桶邊界。
     *
     * @param estimatedTokens 當前請求的預估 Token 數量
     * @param settings        當前負載均衡配置
     * @return 如有更新則返回新的邊界與權重，否則返回 null
     */
    BucketUpdate maybeUpdateDynamicBoundaries(int estimatedTokens, LoadBalancingSettings settings) {
        synchronized (histogramLock) {
            tokenHistogram.addLast(Math.max(1, estimatedTokens));
            while (tokenHistogram.size() > settings.getHistogramSampleSize()) {
                tokenHistogram.removeFirst();
            }
            if (!settings.isDynamicBucketingEnabled()) {
                return null;
            }
            if (tokenHistogram.size() < 32) {
                return null;
            }
            long now = System.currentTimeMillis();
            int intervalSec = computeAdaptiveBucketUpdateIntervalSeconds(
                    tokenHistogram,
                    activeBucketRanges,
                    settings
            );
            if (now - lastBoundaryUpdateMs < intervalSec * 1000L) {
                return null;
            }
            lastBoundaryUpdateMs = now;

            List<Integer> sorted = new ArrayList<>(tokenHistogram);
            sorted.sort(Integer::compareTo);
            int count = Math.max(5, Math.min(6, settings.getBucketCount()));
            List<Integer> updated = new ArrayList<>(count);
            int prev = 64;
            for (int i = 1; i <= count; i++) {
                double q = (double) i / (double) count;
                int idx = (int) Math.floor((sorted.size() - 1) * q);
                int value = Math.max(prev + 1, sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx))));
                updated.add(value);
                prev = value;
            }
            this.activeBucketRanges = updated;
            this.activeBucketWeights = parseBucketWeights(settings, updated.size());
            return new BucketUpdate(this.activeBucketRanges, this.activeBucketWeights);
        }
    }

    /**
     * 解析分桶邊界配置。
     */
    private List<Integer> parseBucketRanges(LoadBalancingSettings cfg, int count) {
        List<Integer> ranges = parseCsvToPositiveInts(cfg.getBucketRanges());
        if (ranges.size() != count) {
            ranges = defaultRangesByContext(cfg.getMaxContextK(), count);
        }
        ranges.sort(Integer::compareTo);
        return ranges;
    }

    /**
     * 解析分桶權重配置。
     */
    private List<Integer> parseBucketWeights(LoadBalancingSettings cfg, int count) {
        List<Integer> weights = parseCsvToPositiveInts(cfg.getBucketWeights());
        if (weights.size() != count) {
            weights = defaultWeights(count);
        }
        return weights;
    }

    private List<Integer> parseCsvToPositiveInts(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] parts = csv.split(",");
        List<Integer> list = new ArrayList<>();
        for (String part : parts) {
            try {
                int v = Integer.parseInt(part.trim());
                if (v > 0) {
                    list.add(v);
                }
            } catch (NumberFormatException ignore) {
                // ignore invalid token
            }
        }
        return list;
    }

    private List<Integer> defaultRangesByContext(int maxContextK, int count) {
        int maxTokens = Math.max(1024, maxContextK * 1024);
        List<Integer> ranges = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ranges.add(Math.max(64, (maxTokens * i) / count));
        }
        return ranges;
    }

    private List<Integer> defaultWeights(int count) {
        List<Integer> weights = new ArrayList<>(count);
        int remain = 100;
        for (int i = 0; i < count; i++) {
            int w = (i == count - 1) ? remain : Math.max(1, remain / (count - i));
            weights.add(w);
            remain -= w;
        }
        return weights;
    }

    /**
     * 計算自適應分桶邊界更新間隔。
     */
    private int computeAdaptiveBucketUpdateIntervalSeconds(ArrayDeque<Integer> histogram,
                                                           List<Integer> bucketRanges,
                                                           LoadBalancingSettings cfg) {
        int minSec = Math.max(3, Math.min(60, cfg.getBucketUpdateIntervalMinSeconds()));
        int maxSec = Math.max(3, Math.min(60, cfg.getBucketUpdateIntervalMaxSeconds()));
        if (minSec > maxSec) {
            int t = minSec;
            minSec = maxSec;
            maxSec = t;
        }
        if (histogram == null || histogram.isEmpty() || bucketRanges == null || bucketRanges.isEmpty()) {
            return Math.min(60, Math.max(3, (minSec + maxSec) / 2));
        }
        int bucketCount = bucketRanges.size();
        int[] counts = new int[bucketCount];
        int total = 0;
        for (Integer v : histogram) {
            if (v == null) {
                continue;
            }
            int tokens = Math.max(1, v);
            int idx = 0;
            while (idx < bucketCount && tokens > bucketRanges.get(idx)) {
                idx++;
            }
            if (idx >= bucketCount) {
                idx = bucketCount - 1;
            }
            counts[idx]++;
            total++;
        }
        if (total <= 0) {
            return 20;
        }
        double[] obs = new double[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            obs[i] = (double) counts[i] / (double) total;
        }

        List<Integer> weights = parseBucketWeights(cfg, bucketCount);
        double sumW = 0.0d;
        for (int w : weights) {
            sumW += Math.max(1, w);
        }
        double[] target = new double[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            target[i] = sumW <= 0 ? (1.0d / bucketCount) : (Math.max(1, weights.get(i)) / sumW);
        }

        double loss = 0.0d;
        for (int i = 0; i < bucketCount; i++) {
            loss += Math.abs(obs[i] - target[i]);
        }
        loss = Math.min(2.0d, Math.max(0.0d, loss)); // L1 distance in [0,2]

        double shift = 0.0d;
        double[] last = lastObservedBucketDist;
        if (last != null && last.length == bucketCount) {
            for (int i = 0; i < bucketCount; i++) {
                shift += Math.abs(obs[i] - last[i]);
            }
            shift = Math.min(2.0d, Math.max(0.0d, shift));
        }
        lastObservedBucketDist = obs;

        // Normalize to [0,1] (0: perfect match & stable, 1: large mismatch or drift)
        double score = 0.7d * (loss / 2.0d) + 0.3d * (shift / 2.0d);
        score = Math.max(0.0d, Math.min(1.0d, score));

        int interval = (int) Math.round(maxSec - score * (maxSec - minSec));
        return Math.max(minSec, Math.min(maxSec, interval));
    }

    /**
     * 分桶更新結果。
     *
     * @param ranges  新的分桶邊界
     * @param weights 新的分桶權重
     */
    record BucketUpdate(List<Integer> ranges, List<Integer> weights) {}
}

