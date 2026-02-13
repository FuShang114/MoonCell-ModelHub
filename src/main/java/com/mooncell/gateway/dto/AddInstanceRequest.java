package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI模型服务实例注册请求DTO
 */
@Schema(description = "AI模型服务实例注册请求")
public class AddInstanceRequest {
    @Schema(description = "AI模型名称", example = "gpt-3.5-turbo", required = true)
    private String model;
    
    @Schema(description = "服务实例URL地址", example = "https://api.openai.com/v1/chat/completions", required = true)
    private String url;    // 核心标识
    
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
    
    @Schema(description = "最大负载限制(并发任务数)", example = "10", required = false)
    private Integer maxQps;

    // Getters and Setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getPostModel() { return postModel; }
    public void setPostModel(String postModel) { this.postModel = postModel; }

    public String getResponseRequestIdPath() { return responseRequestIdPath; }
    public void setResponseRequestIdPath(String responseRequestIdPath) { this.responseRequestIdPath = responseRequestIdPath; }

    public String getResponseContentPath() { return responseContentPath; }
    public void setResponseContentPath(String responseContentPath) { this.responseContentPath = responseContentPath; }

    public String getResponseSeqPath() { return responseSeqPath; }
    public void setResponseSeqPath(String responseSeqPath) { this.responseSeqPath = responseSeqPath; }

    public Boolean getResponseRawEnabled() { return responseRawEnabled; }
    public void setResponseRawEnabled(Boolean responseRawEnabled) { this.responseRawEnabled = responseRawEnabled; }
    
    public Integer getMaxQps() { return maxQps; }
    public void setMaxQps(Integer maxQps) { this.maxQps = maxQps; }
}