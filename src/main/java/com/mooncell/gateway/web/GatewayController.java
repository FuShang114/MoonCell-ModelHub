package com.mooncell.gateway.web;

import com.mooncell.gateway.api.OpenAiRequest;
import com.mooncell.gateway.service.GatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@Tag(name = "AI网关控制器", description = "统一AI模型访问入口，支持负载均衡和幂等控制")
public class GatewayController {
    private final GatewayService gatewayService;

    /**
     * 统一入口：接收前端的业务请求（只包含 message + 幂等键）
     * - 直接通过 WebClient 调用下游大模型服务
     * - 使用 Redis 做幂等控制
     * - 使用 ResourceLockManager 做 Fast Fail 限流
     */
    @Operation(
        summary = "AI模型统一聊天接口",
        description = "通过网关统一调用各种AI模型，支持负载均衡、幂等控制和实时流式响应",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "AI聊天请求",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = OpenAiRequest.class)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "成功，返回AI响应流"),
        @ApiResponse(responseCode = "400", description = "请求参数错误"),
        @ApiResponse(responseCode = "409", description = "重复请求（幂等控制）"),
        @ApiResponse(responseCode = "503", description = "服务繁忙")
    })
    @PostMapping(value = "/v1/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Parameter(description = "AI聊天请求对象") @RequestBody OpenAiRequest request) {
        return gatewayService.chat(request);
    }
}


