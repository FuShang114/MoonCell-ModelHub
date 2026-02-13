package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "模型服务商信息")
public class ProviderDto {
    private Long id;
    private String name;
    private String description;

    public ProviderDto() {}

    public ProviderDto(Long id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
