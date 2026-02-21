package com.mooncell.gateway.core.converter;

import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转换器工厂
 * <p>
 * 负责管理和缓存转换器实例，提供统一的转换器获取接口。
 * 支持转换器缓存以提高性能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConverterFactory {
    
    private final List<RequestConverter> requestConverters;
    private final List<ResponseConverter> responseConverters;
    
    // 转换器缓存：instanceId -> converter
    private final Map<Long, RequestConverter> requestConverterCache = new ConcurrentHashMap<>();
    private final Map<Long, ResponseConverter> responseConverterCache = new ConcurrentHashMap<>();
    
    /**
     * 获取请求转换器
     *
     * @param instance 模型实例
     * @return 请求转换器
     */
    public RequestConverter getRequestConverter(ModelInstance instance) {
        Long instanceId = instance.getId();
        if (instanceId == null) {
            // 如果没有 ID，直接返回第一个支持的转换器
            return requestConverters.stream()
                .filter(converter -> converter.supports(instance))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No request converter found for instance"));
        }
        
        // 从缓存获取
        return requestConverterCache.computeIfAbsent(instanceId, id -> {
            return requestConverters.stream()
                .filter(converter -> converter.supports(instance))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No request converter found for instance: " + id));
        });
    }
    
    /**
     * 获取响应转换器
     *
     * @param instance 模型实例
     * @return 响应转换器
     */
    public ResponseConverter getResponseConverter(ModelInstance instance) {
        Long instanceId = instance.getId();
        if (instanceId == null) {
            // 如果没有 ID，直接返回第一个支持的转换器
            return responseConverters.stream()
                .filter(converter -> converter.supports(instance))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No response converter found for instance"));
        }
        
        // 从缓存获取
        return responseConverterCache.computeIfAbsent(instanceId, id -> {
            return responseConverters.stream()
                .filter(converter -> converter.supports(instance))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No response converter found for instance: " + id));
        });
    }
    
    /**
     * 清除转换器缓存（当实例配置更新时调用）
     *
     * @param instanceId 实例 ID
     */
    public void evictCache(Long instanceId) {
        if (instanceId != null) {
            requestConverterCache.remove(instanceId);
            responseConverterCache.remove(instanceId);
        }
    }
    
    /**
     * 清除所有缓存
     */
    public void clearCache() {
        requestConverterCache.clear();
        responseConverterCache.clear();
    }
}
