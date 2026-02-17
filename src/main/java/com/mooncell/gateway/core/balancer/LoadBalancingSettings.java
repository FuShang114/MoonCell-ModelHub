package com.mooncell.gateway.core.balancer;

public class LoadBalancingSettings {
    private LoadBalancingAlgorithm algorithm;
    private int sampleCount;
    private boolean dynamicBucketingEnabled;
    private int histogramSampleSize;
    private int bucketUpdateIntervalSeconds;
    private int shortBucketWeight;
    private int mediumBucketWeight;
    private int longBucketWeight;

    public static LoadBalancingSettings defaultSettings() {
        LoadBalancingSettings settings = new LoadBalancingSettings();
        settings.setAlgorithm(LoadBalancingAlgorithm.TRADITIONAL);
        settings.setSampleCount(2);
        settings.setDynamicBucketingEnabled(true);
        settings.setHistogramSampleSize(600);
        settings.setBucketUpdateIntervalSeconds(15);
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

    public boolean isDynamicBucketingEnabled() {
        return dynamicBucketingEnabled;
    }

    public void setDynamicBucketingEnabled(boolean dynamicBucketingEnabled) {
        this.dynamicBucketingEnabled = dynamicBucketingEnabled;
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
        this.bucketUpdateIntervalSeconds = Math.max(5, bucketUpdateIntervalSeconds);
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

    public LoadBalancingSettings copy() {
        LoadBalancingSettings copy = new LoadBalancingSettings();
        copy.setAlgorithm(this.algorithm);
        copy.setSampleCount(this.sampleCount);
        copy.setDynamicBucketingEnabled(this.dynamicBucketingEnabled);
        copy.setHistogramSampleSize(this.histogramSampleSize);
        copy.setBucketUpdateIntervalSeconds(this.bucketUpdateIntervalSeconds);
        copy.setShortBucketWeight(this.shortBucketWeight);
        copy.setMediumBucketWeight(this.mediumBucketWeight);
        copy.setLongBucketWeight(this.longBucketWeight);
        return copy;
    }
}
