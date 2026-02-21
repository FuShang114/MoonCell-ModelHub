package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 健康实例DTO
 * 用于前端显示健康节点信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "健康实例信息")
public class HealthyInstanceDto {

    @Schema(description = "实例ID")
    private Long id;

    @Schema(description = "实例名称")
    private String name;

    @Schema(description = "服务商名称")
    private String providerName;

    @Schema(description = "实例基础URL")
    private String url;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "实例配置的每分钟请求上限（RPM）")
    private Integer rpmLimit;

    @Schema(description = "实例配置的每分钟Token上限（TPM）")
    private Integer tpmLimit;

    @Schema(description = "当前累计请求次数")
    private Integer requestCount;

    @Schema(description = "当前健康状态")
    private Boolean healthy;

    @Schema(description = "最近一次心跳时间（字符串展示）")
    private String lastHeartbeat;

    @Schema(description = "累计失败次数")
    private Integer failureCount;
}