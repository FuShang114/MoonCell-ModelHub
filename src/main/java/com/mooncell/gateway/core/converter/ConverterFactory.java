package com.mooncell.gateway.core.converter;

import com.mooncell.gateway.core.converter.impl.RuleBasedRequestConverter;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 转换器工厂
 * <p>
 * 目前仅负责提供 RuleBasedRequestConverter。
 * 所有请求转换逻辑都统一走规则引擎，不再保留 OpenAPI 兼容转换器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConverterFactory {
    
    /**
     * 基于规则的请求转换器。
     */
    private final RuleBasedRequestConverter ruleBasedRequestConverter;
    
    /**
     * 获取请求转换器
     *
     * @param instance 模型实例
     * @return 请求转换器
     */
    public RequestConverter getRequestConverter(ModelInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("ModelInstance must not be null");
        }
        // 所有实例统一走规则引擎；规则缺失或错误时由 RuleBasedRequestConverter 自己抛错
        return ruleBasedRequestConverter;
    }
}
