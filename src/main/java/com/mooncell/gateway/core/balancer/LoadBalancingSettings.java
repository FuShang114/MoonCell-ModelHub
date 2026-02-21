package com.mooncell.gateway.core.balancer;

/**
 * 负载均衡配置类
 * 
 * <p>包含负载均衡器的所有可配置参数。
 * 
 * <p>主要配置项：
 * <ul>
 *   <li><b>算法配置</b>：负载均衡算法（当前仅 TRADITIONAL）</li>
 *   <li><b>采样配置</b>：采样数量、轮数等</li>
 *   <li><b>分桶配置</b>：分桶数量、边界、权重等</li>
 *   <li><b>队列配置</b>：队列容量限制</li>
 *   <li><b>资源池配置</b>：多池顺序配置</li>
 * </ul>
 * 
 * <p>所有参数都有默认值，并通过 setter 方法进行范围校验和修正。
 */
public class LoadBalancingSettings {
    /** 负载均衡算法 */
    private LoadBalancingAlgorithm algorithm;
    /** 每轮采样的实例数量 */
    private int sampleCount;
    /** 采样轮数，如果一轮采样失败则尝试下一轮 */
    private int samplingRounds;
    /** 对象池策略中从对象池采样的对象数量 */
    private int samplingSize;
    /** 是否启用动态分桶（根据请求分布自动调整分桶边界） */
    private boolean dynamicBucketingEnabled;
    /** 最大上下文长度（K），用于计算默认分桶边界 */
    private int maxContextK;
    /** 分桶数量（5 或 6） */
    private int bucketCount;
    /** 分桶边界（逗号分隔的 Token 上限值） */
    private String bucketRanges;
    /** 分桶权重（逗号分隔的权重值） */
    private String bucketWeights;
    /** Token 直方图样本大小（用于动态分桶） */
    private int histogramSampleSize;
    /** 分桶边界更新间隔（秒），已废弃，使用自适应间隔 */
    private int bucketUpdateIntervalSeconds;
    /** 分桶边界自适应刷新间隔下限（秒），范围 [3, 60] */
    private int bucketUpdateIntervalMinSeconds;
    /** 分桶边界自适应刷新间隔上限（秒），范围 [3, 60] */
    private int bucketUpdateIntervalMaxSeconds;
    /** 资源池顺序（逗号分隔），路由器按此顺序尝试；当前池无可用对象时自动试下一池。默认 "default" */
    private String orderedPoolKeys;
    private int queueCapacity;
    private int tTuneIntervalSeconds;
    private int tCasRetrySampleSize;
    private double tRejectHighThreshold;
    private double tForcedReleaseHighThreshold;
    private double tCasRetryP95HighThreshold;
    private int shortBucketWeight;
    private int mediumBucketWeight;
    private int longBucketWeight;

    /**
     * 创建默认配置
     * 
     * <p>返回所有参数都使用默认值的配置对象。
     * 
     * @return 默认配置实例
     */
    public static LoadBalancingSettings defaultSettings() {
        LoadBalancingSettings settings = new LoadBalancingSettings();
        settings.setAlgorithm(LoadBalancingAlgorithm.TRADITIONAL);
        settings.setSampleCount(2);
        settings.setSamplingRounds(2);
        settings.setSamplingSize(3);
        settings.setDynamicBucketingEnabled(true);
        settings.setMaxContextK(32);
        settings.setBucketCount(5);
        settings.setBucketRanges("1024,2048,4096,8192,16384");
        settings.setBucketWeights("30,25,20,15,10");
        settings.setHistogramSampleSize(600);
        settings.setBucketUpdateIntervalSeconds(15);
        settings.setBucketUpdateIntervalMinSeconds(3);
        settings.setBucketUpdateIntervalMaxSeconds(60);
        settings.setOrderedPoolKeys("default");
        settings.setQueueCapacity(128);
        settings.setTTuneIntervalSeconds(300);
        settings.setTCasRetrySampleSize(256);
        settings.setTRejectHighThreshold(0.30d);
        settings.setTForcedReleaseHighThreshold(0.20d);
        settings.setTCasRetryP95HighThreshold(2.5d);
        settings.setShortBucketWeight(45);
        settings.setMediumBucketWeight(35);
        settings.setLongBucketWeight(20);
        return settings;
    }

    public LoadBalancingAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(LoadBalancingAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = Math.max(1, sampleCount);
    }

    public int getSamplingRounds() {
        return samplingRounds;
    }

    public void setSamplingRounds(int samplingRounds) {
        this.samplingRounds = Math.max(1, samplingRounds);
    }

    public int getSamplingSize() {
        return samplingSize;
    }

    public void setSamplingSize(int samplingSize) {
        this.samplingSize = Math.max(1, samplingSize);
    }

    public boolean isDynamicBucketingEnabled() {
        return dynamicBucketingEnabled;
    }

    public void setDynamicBucketingEnabled(boolean dynamicBucketingEnabled) {
        this.dynamicBucketingEnabled = dynamicBucketingEnabled;
    }

    public int getMaxContextK() {
        return maxContextK;
    }

    public void setMaxContextK(int maxContextK) {
        this.maxContextK = Math.max(1, maxContextK);
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(int bucketCount) {
        this.bucketCount = Math.max(5, Math.min(6, bucketCount));
    }

    public String getBucketRanges() {
        return bucketRanges;
    }

    public void setBucketRanges(String bucketRanges) {
        this.bucketRanges = (bucketRanges == null || bucketRanges.isBlank())
                ? "1024,2048,4096,8192,16384" : bucketRanges.trim();
    }

    public String getBucketWeights() {
        return bucketWeights;
    }

    public void setBucketWeights(String bucketWeights) {
        this.bucketWeights = (bucketWeights == null || bucketWeights.isBlank())
                ? "30,25,20,15,10" : bucketWeights.trim();
    }

    public int getHistogramSampleSize() {
        return histogramSampleSize;
    }

    public void setHistogramSampleSize(int histogramSampleSize) {
        this.histogramSampleSize = Math.max(100, histogramSampleSize);
    }

    public int getBucketUpdateIntervalSeconds() {
        return bucketUpdateIntervalSeconds;
    }

    public void setBucketUpdateIntervalSeconds(int bucketUpdateIntervalSeconds) {
        this.bucketUpdateIntervalSeconds = clampBucketInterval(bucketUpdateIntervalSeconds);
    }

    public int getBucketUpdateIntervalMinSeconds() {
        return bucketUpdateIntervalMinSeconds <= 0 ? 3 : bucketUpdateIntervalMinSeconds;
    }

    public void setBucketUpdateIntervalMinSeconds(int bucketUpdateIntervalMinSeconds) {
        this.bucketUpdateIntervalMinSeconds = clampBucketInterval(bucketUpdateIntervalMinSeconds);
    }

    public int getBucketUpdateIntervalMaxSeconds() {
        return bucketUpdateIntervalMaxSeconds <= 0 ? 60 : bucketUpdateIntervalMaxSeconds;
    }

    public void setBucketUpdateIntervalMaxSeconds(int bucketUpdateIntervalMaxSeconds) {
        this.bucketUpdateIntervalMaxSeconds = clampBucketInterval(bucketUpdateIntervalMaxSeconds);
    }

    /** 分桶刷新间隔允许范围 [3, 60] 秒 */
    private static int clampBucketInterval(int seconds) {
        return Math.max(3, Math.min(60, seconds));
    }

    public String getOrderedPoolKeys() {
        return orderedPoolKeys;
    }

    public void setOrderedPoolKeys(String orderedPoolKeys) {
        this.orderedPoolKeys = (orderedPoolKeys == null || orderedPoolKeys.isBlank()) ? "default" : orderedPoolKeys.trim();
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    public int getTTuneIntervalSeconds() {
        return tTuneIntervalSeconds;
    }

    public void setTTuneIntervalSeconds(int tTuneIntervalSeconds) {
        this.tTuneIntervalSeconds = Math.max(30, tTuneIntervalSeconds);
    }

    public int getTCasRetrySampleSize() {
        return tCasRetrySampleSize;
    }

    public void setTCasRetrySampleSize(int tCasRetrySampleSize) {
        this.tCasRetrySampleSize = Math.max(32, tCasRetrySampleSize);
    }

    public double getTRejectHighThreshold() {
        return tRejectHighThreshold;
    }

    public void setTRejectHighThreshold(double tRejectHighThreshold) {
        this.tRejectHighThreshold = clampRate(tRejectHighThreshold, 0.30d);
    }

    public double getTForcedReleaseHighThreshold() {
        return tForcedReleaseHighThreshold;
    }

    public void setTForcedReleaseHighThreshold(double tForcedReleaseHighThreshold) {
        this.tForcedReleaseHighThreshold = clampRate(tForcedReleaseHighThreshold, 0.20d);
    }

    public double getTCasRetryP95HighThreshold() {
        return tCasRetryP95HighThreshold;
    }

    public void setTCasRetryP95HighThreshold(double tCasRetryP95HighThreshold) {
        this.tCasRetryP95HighThreshold = Math.max(0.1d, tCasRetryP95HighThreshold);
    }

    public int getShortBucketWeight() {
        return shortBucketWeight;
    }

    public void setShortBucketWeight(int shortBucketWeight) {
        this.shortBucketWeight = Math.max(1, shortBucketWeight);
    }

    public int getMediumBucketWeight() {
        return mediumBucketWeight;
    }

    public void setMediumBucketWeight(int mediumBucketWeight) {
        this.mediumBucketWeight = Math.max(1, mediumBucketWeight);
    }

    public int getLongBucketWeight() {
        return longBucketWeight;
    }

    public void setLongBucketWeight(int longBucketWeight) {
        this.longBucketWeight = Math.max(1, longBucketWeight);
    }

    /**
     * 深拷贝配置对象
     * 
     * <p>创建当前配置的完整副本，所有字段都会被复制。
     * 
     * @return 新的配置对象副本
     */
    public LoadBalancingSettings copy() {
        LoadBalancingSettings copy = new LoadBalancingSettings();
        copy.setAlgorithm(this.algorithm);
        copy.setSampleCount(this.sampleCount);
        copy.setSamplingRounds(this.samplingRounds);
        copy.setSamplingSize(this.samplingSize);
        copy.setDynamicBucketingEnabled(this.dynamicBucketingEnabled);
        copy.setMaxContextK(this.maxContextK);
        copy.setBucketCount(this.bucketCount);
        copy.setBucketRanges(this.bucketRanges);
        copy.setBucketWeights(this.bucketWeights);
        copy.setHistogramSampleSize(this.histogramSampleSize);
        copy.setBucketUpdateIntervalSeconds(this.bucketUpdateIntervalSeconds);
        copy.setBucketUpdateIntervalMinSeconds(this.bucketUpdateIntervalMinSeconds);
        copy.setBucketUpdateIntervalMaxSeconds(this.bucketUpdateIntervalMaxSeconds);
        copy.setOrderedPoolKeys(this.orderedPoolKeys);
        copy.setQueueCapacity(this.queueCapacity);
        copy.setTTuneIntervalSeconds(this.tTuneIntervalSeconds);
        copy.setTCasRetrySampleSize(this.tCasRetrySampleSize);
        copy.setTRejectHighThreshold(this.tRejectHighThreshold);
        copy.setTForcedReleaseHighThreshold(this.tForcedReleaseHighThreshold);
        copy.setTCasRetryP95HighThreshold(this.tCasRetryP95HighThreshold);
        copy.setShortBucketWeight(this.shortBucketWeight);
        copy.setMediumBucketWeight(this.mediumBucketWeight);
        copy.setLongBucketWeight(this.longBucketWeight);
        return copy;
    }

    private double clampRate(double value, double defaultValue) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return defaultValue;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
