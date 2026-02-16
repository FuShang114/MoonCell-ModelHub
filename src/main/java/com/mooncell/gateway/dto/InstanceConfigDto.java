package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 实例配置DTO
 */
@Schema(description = "AI模型服务实例配置")
public class InstanceConfigDto {
    private Long id;
    private String providerName;
    private String modelName;
    private String url;
    private String apiKey;
    private Integer rpmLimit;
    private Integer tpmLimit;
    private Boolean isActive;
    private String postModel;
    private String responseRequestIdPath;
    private String responseContentPath;
    private String responseSeqPath;
    private Boolean responseRawEnabled;

    public InstanceConfigDto() {}

    public InstanceConfigDto(Long id, String providerName, String modelName, String url,
                             String apiKey, Integer rpmLimit, Integer tpmLimit,
                             Boolean isActive, String postModel,
                             String responseRequestIdPath, String responseContentPath, String responseSeqPath,
                             Boolean responseRawEnabled) {
        this.id = id;
        this.providerName = providerName;
        this.modelName = modelName;
        this.url = url;
        this.apiKey = apiKey;
        this.rpmLimit = rpmLimit;
        this.tpmLimit = tpmLimit;
        this.isActive = isActive;
        this.postModel = postModel;
        this.responseRequestIdPath = responseRequestIdPath;
        this.responseContentPath = responseContentPath;
        this.responseSeqPath = responseSeqPath;
        this.responseRawEnabled = responseRawEnabled;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Integer getRpmLimit() { return rpmLimit; }
    public void setRpmLimit(Integer rpmLimit) { this.rpmLimit = rpmLimit; }

    public Integer getTpmLimit() { return tpmLimit; }
    public void setTpmLimit(Integer tpmLimit) { this.tpmLimit = tpmLimit; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

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
}
