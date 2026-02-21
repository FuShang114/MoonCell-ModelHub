package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI模型服务实例注册请求DTO
 */
@Data
@Schema(description = "AI模型服务实例注册请求")
public class AddInstanceRequest {
    @Schema(description = "AI模型名称", example = "gpt-3.5-turbo", required = true)
    private String model;
    
    @Schema(description = "服务实例URL地址", example = "https://api.openai.com/v1/chat/completions", required = true)
    private String url;
    
    @Schema(description = "服务商名称", example = "openai", required = true)
    private String provider;
    
    @Schema(description = "API密钥", example = "sk-xxxxxxxxxxxxxxxx", required = true)
    private String apiKey;
    
    @Schema(description = "请求体模板(JSON字符串)", example = "{\"stream\":true,\"messages\":\"$messages\"}", required = false)
    private String postModel;

    @Schema(description = "响应requestId字段路径(点路径)", example = "id", required = false)
    private String responseRequestIdPath;

    @Schema(description = "响应content字段路径(点路径)", example = "choices.0.delta.content", required = false)
    private String responseContentPath;

    @Schema(description = "响应seq字段路径(点路径)", example = "choices.0.index", required = false)
    private String responseSeqPath;

    @Schema(description = "是否原始输出", example = "false", required = false)
    private Boolean responseRawEnabled;
    
    @Schema(description = "每分钟请求上限(RPM)", example = "600", required = false)
    private Integer rpmLimit;

    @Schema(description = "每分钟Token上限(TPM)", example = "600000", required = false)
    private Integer tpmLimit;

    @Schema(description = "兼容字段：旧版最大QPS，后续废弃", example = "10", required = false)
    private Integer maxQps;
}