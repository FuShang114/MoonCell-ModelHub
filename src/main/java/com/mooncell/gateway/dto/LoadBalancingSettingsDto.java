package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 负载均衡配置DTO
 * 用于前端配置和API传输负载均衡算法相关参数
 */
@Data
@Schema(description = "负载均衡运行时配置")
public class LoadBalancingSettingsDto {

    @Schema(description = "当前使用的负载均衡算法", example = "TRADITIONAL")
    private String algorithm;

    @Schema(description = "每轮采样的总样本数", example = "32")
    private Integer sampleCount;

    @Schema(description = "采样轮数", example = "3")
    private Integer samplingRounds;

    @Schema(description = "每轮采样个数（部分算法自动等于 sampleCount）", example = "32")
    private Integer samplingSize;

    @Schema(description = "是否开启动态分桶", example = "true")
    private Boolean dynamicBucketingEnabled;

    @Schema(description = "最大上下文长度K（用于计算桶边界）", example = "16")
    private Integer maxContextK;

    @Schema(description = "分桶数量", example = "8")
    private Integer bucketCount;

    @Schema(description = "分桶边界配置，逗号分隔的token阈值", example = "512,1024,2048")
    private String bucketRanges;

    @Schema(description = "分桶权重配置，逗号分隔", example = "3,2,1")
    private String bucketWeights;

    @Schema(description = "直方图采样窗口大小", example = "200")
    private Integer histogramSampleSize;

    @Schema(description = "分桶配置刷新间隔（秒）", example = "60")
    private Integer bucketUpdateIntervalSeconds;

    @Schema(description = "分桶刷新最小间隔（秒）", example = "30")
    private Integer bucketUpdateIntervalMinSeconds;

    @Schema(description = "分桶刷新最大间隔（秒）", example = "300")
    private Integer bucketUpdateIntervalMaxSeconds;

    @Schema(description = "对象池有序键，逗号分隔", example = "default,backup")
    private String orderedPoolKeys;

    @Schema(description = "队列容量上限", example = "1000")
    private Integer queueCapacity;

    @Schema(description = "T 调优周期（秒）", example = "60")
    private Integer tTuneIntervalSeconds;

    @Schema(description = "T CAS 重试采样窗口大小", example = "500")
    private Integer tCasRetrySampleSize;

    @Schema(description = "T 拒绝率上升阈值", example = "0.05")
    private Double tRejectHighThreshold;

    @Schema(description = "T 强制释放率上升阈值", example = "0.01")
    private Double tForcedReleaseHighThreshold;

    @Schema(description = "T CAS 重试 P95 上升阈值", example = "5.0")
    private Double tCasRetryP95HighThreshold;

    @Schema(description = "短请求分桶权重", example = "3")
    private Integer shortBucketWeight;

    @Schema(description = "中等请求分桶权重", example = "2")
    private Integer mediumBucketWeight;

    @Schema(description = "长请求分桶权重", example = "1")
    private Integer longBucketWeight;
}
