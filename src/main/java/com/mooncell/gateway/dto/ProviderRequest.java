package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "服务商创建/更新请求")
public class ProviderRequest {
    @Schema(description = "服务商名称", example = "openai")
    private String name;
    @Schema(description = "服务商描述", example = "OpenAI 官方服务")
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
