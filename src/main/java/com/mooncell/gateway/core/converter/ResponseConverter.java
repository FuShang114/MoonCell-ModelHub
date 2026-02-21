package com.mooncell.gateway.core.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.mooncell.gateway.core.model.ModelInstance;

import java.util.List;

/**
 * 响应转换器接口
 * <p>
 * 负责将实例特定的响应格式转换为网关统一的响应格式。
 * 支持基于 OpenAPI Schema 或配置规则的转换。
 */
public interface ResponseConverter {
    
    /**
     * 将实例响应转换为网关统一格式
     *
     * @param instance        模型实例
     * @param instanceResponse 实例原始响应（JSON 节点）
     * @param defaultRequestId 默认请求 ID（用于填充缺失字段）
     * @param seqCounter      序号计数器（用于生成序号）
     * @return 转换后的响应字符串列表（SSE 格式）
     */
    List<String> convert(ModelInstance instance, JsonNode instanceResponse, 
                        String defaultRequestId, java.util.concurrent.atomic.AtomicInteger seqCounter);
    
    /**
     * 检查是否支持该实例的转换
     *
     * @param instance 模型实例
     * @return 是否支持
     */
    boolean supports(ModelInstance instance);
}
