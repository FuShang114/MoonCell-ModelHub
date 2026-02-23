package com.mooncell.gateway.core.converter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 转换规则定义
 * <p>
 * 用于描述如何将 OpenAPI 格式转换为服务商特定格式，以及如何将服务商响应转换为 OpenAPI 格式。
 * 转换规则是一个 JSON 对象，包含字段映射、值转换等信息。
 */
@Data
public class ConversionRule {
    
    /**
     * 请求转换规则
     * <p>
     * 格式示例：
     * {
     *   "type": "template",  // 或 "mapping"
     *   "template": "{...}",  // 模板模式：使用 JSON 模板
     *   "mapping": {          // 映射模式：字段映射
     *     "model": "model",
     *     "messages": "input.messages",
     *     "stream": "stream",
     *     "temperature": "parameters.temperature"
     *   },
     *   "transformations": {  // 值转换规则
     *     "messages": "array_to_string"  // 将 messages 数组转换为字符串
     *   }
     * }
     */
    private JsonNode requestRule;
    
    /**
     * 响应转换规则
     * <p>
     * 支持两种模式：
     * <p>
     * 1. 映射模式（mapping）：
     * {
     *   "type": "mapping",
     *   "requestIdPath": "id",           // 请求 ID 路径
     *   "contentPath": "output.text",    // 内容路径
     *   "seqPath": "output.index",       // 序号路径
     *   "transformations": {              // 值转换规则
     *     "content": "trim"               // 对内容进行 trim
     *   }
     * }
     * <p>
     * 2. 模板模式（template）：
     * {
     *   "type": "template",
     *   "template": {
     *     "id": "$requestId",
     *     "object": "$object",
     *     "model": "$model",
     *     "choices": [{
     *       "index": "$seq",
     *       "delta": {
     *         "content": "$content"
     *       }
     *     }]
     *   }
     * }
     * <p>
     * 支持的占位符：
     * - $requestId 或 $id: 请求 ID
     * - $content: 内容
     * - $seq 或 $index: 序号
     * - $model: 模型名称
     * - $object: 对象类型（默认 "chat.completion.chunk"）
     * - $path.field: 从实例响应中读取指定路径的值
     */
    private JsonNode responseRule;
}
