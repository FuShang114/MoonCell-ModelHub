package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "测试实例请求")
public class TestInstanceRequest {
    @Schema(description = "测试消息内容", example = "Hello, this is a test message")
    private String testMessage;
}
