package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用于“未保存配置”的实例测试请求。
 */
@Data
public class TestInstanceConfigRequest {

    @Schema(description = "实例配置（与新增/更新实例相同）")
    private AddInstanceRequest instance;

    @Schema(description = "测试消息内容", example = "ping")
    private String testMessage;
}

