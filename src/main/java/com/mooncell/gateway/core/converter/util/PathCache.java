package com.mooncell.gateway.core.converter.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 路径分割缓存工具类
 * <p>
 * 优化：缓存路径分割结果，避免重复调用 split() 创建临时数组。
 * 路径字符串通常较短且重复度高，缓存效果好。
 */
public class PathCache {
    
    private static final ConcurrentHashMap<String, String[]> CACHE = new ConcurrentHashMap<>();
    
    /**
     * 分割路径（带缓存）
     * 
     * @param path 点分路径，如 "choices.0.delta.content"
     * @return 分割后的字符串数组
     */
    public static String[] splitPath(String path) {
        if (path == null || path.isEmpty()) {
            return new String[0];
        }
        
        // 从缓存获取
        return CACHE.computeIfAbsent(path, p -> p.split("\\."));
    }
    
    /**
     * 清除缓存（用于测试或内存管理）
     */
    public static void clearCache() {
        CACHE.clear();
    }
    
    /**
     * 获取缓存大小（用于监控）
     */
    public static int getCacheSize() {
        return CACHE.size();
    }
}
