package com.mooncell.gateway.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 监控指标DTO
 * 包含系统监控的各项指标和时序数据
 */
@Data
public class MonitorMetricsDto {
    private Double gcRatePerMin;
    private Double cpuUsage;
    private Double qps;
    private Double successRate;
    private Double failureRate;
    private Double throughput;
    private Double resourceUsage;

    private List<MetricPointDto> gcRateSeries = new ArrayList<>();
    private List<MetricPointDto> cpuUsageSeries = new ArrayList<>();
    private List<MetricPointDto> qpsSeries = new ArrayList<>();
    private List<MetricPointDto> successRateSeries = new ArrayList<>();
    private List<MetricPointDto> failureRateSeries = new ArrayList<>();
    private List<MetricPointDto> throughputSeries = new ArrayList<>();
    private List<MetricPointDto> resourceUsageSeries = new ArrayList<>();

    /**
     * 最近一个采样窗口内的失败原因分布
     */
    private List<FailureReasonStatDto> failureReasons = new ArrayList<>();
}
