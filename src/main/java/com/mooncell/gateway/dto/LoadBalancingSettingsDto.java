package com.mooncell.gateway.dto;

public class LoadBalancingSettingsDto {
    private String algorithm;
    private Integer sampleCount;
    private Boolean dynamicBucketingEnabled;
    private Integer histogramSampleSize;
    private Integer bucketUpdateIntervalSeconds;
    private Integer shortBucketWeight;
    private Integer mediumBucketWeight;
    private Integer longBucketWeight;

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Integer getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(Integer sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Boolean getDynamicBucketingEnabled() {
        return dynamicBucketingEnabled;
    }

    public void setDynamicBucketingEnabled(Boolean dynamicBucketingEnabled) {
        this.dynamicBucketingEnabled = dynamicBucketingEnabled;
    }

    public Integer getHistogramSampleSize() {
        return histogramSampleSize;
    }

    public void setHistogramSampleSize(Integer histogramSampleSize) {
        this.histogramSampleSize = histogramSampleSize;
    }

    public Integer getBucketUpdateIntervalSeconds() {
        return bucketUpdateIntervalSeconds;
    }

    public void setBucketUpdateIntervalSeconds(Integer bucketUpdateIntervalSeconds) {
        this.bucketUpdateIntervalSeconds = bucketUpdateIntervalSeconds;
    }

    public Integer getShortBucketWeight() {
        return shortBucketWeight;
    }

    public void setShortBucketWeight(Integer shortBucketWeight) {
        this.shortBucketWeight = shortBucketWeight;
    }

    public Integer getMediumBucketWeight() {
        return mediumBucketWeight;
    }

    public void setMediumBucketWeight(Integer mediumBucketWeight) {
        this.mediumBucketWeight = mediumBucketWeight;
    }

    public Integer getLongBucketWeight() {
        return longBucketWeight;
    }

    public void setLongBucketWeight(Integer longBucketWeight) {
        this.longBucketWeight = longBucketWeight;
    }
}
