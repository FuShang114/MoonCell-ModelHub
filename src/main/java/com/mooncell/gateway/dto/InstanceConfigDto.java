package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实例配置DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI模型服务实例配置")
public class InstanceConfigDto {
    @Schema(description = "实例ID")
    private Long id;

    @Schema(description = "服务商名称", example = "openai")
    private String providerName;

    @Schema(description = "模型名称", example = "gpt-3.5-turbo")
    private String modelName;

    @Schema(description = "实例基础URL", example = "https://api.openai.com/v1")
    private String url;

    @Schema(description = "API密钥（脱敏展示）")
    private String apiKey;

    @Schema(description = "每分钟请求上限(RPM)", example = "600")
    private Integer rpmLimit;

    @Schema(description = "每分钟Token上限(TPM)", example = "600000")
    private Integer tpmLimit;

    @Schema(description = "实例是否激活", example = "true")
    private Boolean isActive;

    @Schema(description = "请求体模板(JSON字符串)")
    private String postModel;

    @Schema(description = "响应requestId字段路径(点路径)", example = "id")
    private String responseRequestIdPath;

    @Schema(description = "响应content字段路径(点路径)", example = "choices.0.delta.content")
    private String responseContentPath;

    @Schema(description = "响应seq字段路径(点路径)", example = "choices.0.index")
    private String responseSeqPath;

    @Schema(description = "是否原始输出", example = "false")
    private Boolean responseRawEnabled;

    @Schema(description = "OpenAPI → 下游请求转换规则（JSON 字符串，实例级，可选）")
    private String requestConversionRule;

    @Schema(description = "下游 SSE → OpenAPI 响应转换规则（JSON 字符串，实例级，可选）")
    private String responseConversionRule;
}
