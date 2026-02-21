package com.mooncell.gateway.core.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.mooncell.gateway.core.model.ModelInstance;

/**
 * 请求转换器接口
 * <p>
 * 负责将网关统一的请求格式转换为实例特定的请求格式。
 * 支持基于 OpenAPI Schema 或配置规则的转换。
 */
public interface RequestConverter {
    
    /**
     * 将网关统一请求转换为实例特定格式
     *
     * @param instance      目标模型实例
     * @param gatewayRequest 网关统一请求（JSON 节点）
     * @return 转换后的请求体（JSON 节点）
     */
    JsonNode convert(ModelInstance instance, JsonNode gatewayRequest);
    
    /**
     * 检查是否支持该实例的转换
     *
     * @param instance 模型实例
     * @return 是否支持
     */
    boolean supports(ModelInstance instance);
}
