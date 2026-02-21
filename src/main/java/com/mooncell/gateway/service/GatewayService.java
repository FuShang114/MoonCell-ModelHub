package com.mooncell.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mooncell.gateway.api.ChatCompletionRequest;
import com.mooncell.gateway.api.OpenAiRequest;
import com.mooncell.gateway.core.balancer.LoadBalancer;
import com.mooncell.gateway.core.converter.ConverterFactory;
import com.mooncell.gateway.core.converter.impl.SseResponseConverter;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayService {

    private final LoadBalancer loadBalancer;
    private final MonitoringMetricsService monitoringMetricsService;
    private final InstanceWebClientManager instanceWebClientManager;  // 使用实例专用 WebClient 管理器
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;  // 使用优化的幂等服务
    private final InflightRequestTracker inflightRequestTracker;
    private final ConverterFactory converterFactory;
    private final SseResponseConverter sseResponseConverter;

    /**
     * 统一对外的聊天/补全入口
     * <p>
     * 完整处理链路包括：<br>
     * 1. 入参校验与幂等控制（基于 Redis）<br>
     * 2. 估算 token 用量并从负载均衡器获取可用实例（综合 RPM/TPM 等限流）<br>
     * 3. 按实例配置构造下游请求体和目标 URL<br>
     * 4. 通过 WebClient 调用下游模型服务，并将 SSE 流转换为统一的 JSON 文本流<br>
     * 5. 在请求全链路中打点监控（QPS、失败原因、吞吐量等），并在结束时释放资源
     *
     * @param request OpenAI 兼容的请求对象，包含 message 及幂等键等信息
     * @return 统一封装后的字符串 Flux，单条为 JSON 文本或特殊标记 "[DONE]"
     */
    public Flux<String> chat(OpenAiRequest request) {
        inflightRequestTracker.onStart();
        // 只在当前请求作用域内使用，用于避免重复记录失败原因
        AtomicBoolean failureRecorded = new AtomicBoolean(false);
        try {
            String rawMessage = request.getMessage();
            if (rawMessage == null || rawMessage.isEmpty()) {
                monitoringMetricsService.recordRequestFailure("BAD_REQUEST");
                failureRecorded.set(true);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be empty");
            }

        // 1. 幂等控制（使用优化的 IdempotencyService，Lua 脚本保证原子性）
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            idempotencyKey = UUID.randomUUID().toString();
        }

        final String finalIdempotencyKey = idempotencyKey;
        // 使用 DEBUG 级别减少日志量，异步日志已配置，不会阻塞请求线程
        if (log.isDebugEnabled()) {
            log.debug("Received request, idempotencyKey={}", finalIdempotencyKey);
        }

        // 使用优化的幂等服务（Lua 脚本原子操作）
        if (!idempotencyService.tryAcquire(finalIdempotencyKey)) {
                monitoringMetricsService.recordRequestFailure("DUPLICATE_REQUEST");
            failureRecorded.set(true);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate request");
            }

        // 2. 从负载均衡获取一个可用实例（整合RPM/TPM限流）
        int estimatedTokens = estimateTotalTokens(rawMessage);
        ModelInstance instance = loadBalancer.getNextAvailableInstance(estimatedTokens);

            if (instance == null) {
                idempotencyService.release(finalIdempotencyKey);
                monitoringMetricsService.recordRequestFailure("NO_INSTANCE_OR_RATE_LIMIT");
                failureRecorded.set(true);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No available instances or rate limit exceeded");
            }

        // 3. 构造下游请求体（使用转换器）
        ObjectNode payload = buildPayloadWithConverter(instance, request);

        ModelInstance finalInstance = instance;

        AtomicInteger seqCounter = new AtomicInteger(0);

        // 4. 转换下游 SSE 为统一输出格式
        // 使用实例专用的 WebClient，每个实例有独立的连接池，连接可以更好地复用
        WebClient instanceWebClient = instanceWebClientManager.getWebClient(finalInstance);
            return instanceWebClient.post()
                .uri(finalInstance.getUrl())
                .headers(headers -> {
                    headers.setBearerAuth(finalInstance.getApiKey());
                    if ("azure".equalsIgnoreCase(finalInstance.getProviderName())) {
                        headers.set("api-key", finalInstance.getApiKey());
                    }
                    headers.set("X-Request-Id", finalIdempotencyKey);
                    headers.set("Idempotency-Key", finalIdempotencyKey);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(chunk -> Flux.fromIterable(
                        sseResponseConverter.convertSseChunk(chunk, finalInstance, finalIdempotencyKey, seqCounter)
                ))
                .doOnError(e -> {
                    // 错误日志保留 ERROR 级别，但通过异步日志处理，不会阻塞请求线程
                    log.error("Downstream error for key {}", finalIdempotencyKey, e);
                    finalInstance.recordFailure();
                    monitoringMetricsService.recordRequestFailure("DOWNSTREAM_ERROR");
                    failureRecorded.set(true);
                })
                .doOnComplete(() -> {
                    finalInstance.recordSuccess(0);
                    monitoringMetricsService.recordRequestSuccess(estimatedTokens);
                })
                .doFinally(signalType -> {
                    // 主动取消（例如客户端超时、压测脚本中断）也计入失败原因
                    if (signalType == SignalType.CANCEL) {
                        monitoringMetricsService.recordRequestFailure("CLIENT_CANCELLED");
                        failureRecorded.set(true);
                    }
                    // 释放幂等键
                    idempotencyService.release(finalIdempotencyKey);
                    inflightRequestTracker.onEnd();
                });
        } catch (RuntimeException e) {
            // 走到这里说明在同步阶段发生了未预期的异常（例如 Redis/负载均衡内部错误等），
            // 这类异常同样应该计入失败统计，否则会出现 totalRequests 增长但 failedRequests 未增长的情况，
            // 进而导致成功率被低估而失败原因列表为空。
            if (!failureRecorded.get()) {
                monitoringMetricsService.recordRequestFailure("UNEXPECTED_ERROR");
            }
            inflightRequestTracker.onEnd();
            throw e;
        }
    }
    
    /**
     * 粗略估算一次请求的总 Token 用量（输入 + 预估输出）
     * <p>
     * 仅用于负载均衡和限流决策，不要求与实际完全一致。
     *
     * @param message 用户原始输入
     * @return 估算的 token 总数
     */
    private int estimateTotalTokens(String message) {
        if (message == null || message.isBlank()) {
            return 1;
        }
        // 粗略估算：中文按 1.6 字/Token，英文按 4 字符/Token，取偏保守值。
        int chars = message.length();
        int estimatedInput = Math.max(1, chars / 3);
        int estimatedOutput = Math.max(64, estimatedInput * 2);
        return estimatedInput + estimatedOutput;
    }

    /**
     * 使用转换器构造下游请求体（新方法）
     * <p>
     * 优先使用转换器架构，支持 OpenAPI 格式转换。
     * 向后兼容：如果请求是旧格式（OpenAiRequest），先转换为标准格式。
     *
     * @param instance 目标模型实例
     * @param request  请求对象（可能是 OpenAiRequest 或 ChatCompletionRequest）
     * @return 可直接序列化为 JSON 的请求体节点
     */
    public ObjectNode buildPayloadWithConverter(ModelInstance instance, Object request) {
        // 将请求转换为标准格式（JsonNode）
        JsonNode gatewayRequest;
        
        if (request instanceof OpenAiRequest) {
            // 兼容旧格式：转换为标准格式
            OpenAiRequest oldRequest = (OpenAiRequest) request;
            gatewayRequest = convertOldRequestToStandard(oldRequest, instance);
        } else if (request instanceof ChatCompletionRequest) {
            // 标准格式：直接转换
            gatewayRequest = objectMapper.valueToTree(request);
        } else {
            // 未知格式：尝试直接转换
            gatewayRequest = objectMapper.valueToTree(request);
        }
        
        // 使用转换器转换为实例特定格式
        var requestConverter = converterFactory.getRequestConverter(instance);
        JsonNode convertedRequest = requestConverter.convert(instance, gatewayRequest);
        
        // 确保返回 ObjectNode
        if (convertedRequest instanceof ObjectNode) {
            return (ObjectNode) convertedRequest;
        } else {
            // 如果不是 ObjectNode，尝试转换
            ObjectNode result = objectMapper.createObjectNode();
            if (convertedRequest.isObject()) {
                convertedRequest.fields().forEachRemaining(entry -> 
                    result.set(entry.getKey(), entry.getValue()));
            }
            return result;
        }
    }
    
    /**
     * 将旧格式请求转换为标准格式
     */
    private JsonNode convertOldRequestToStandard(OpenAiRequest oldRequest, ModelInstance instance) {
        ObjectNode standardRequest = objectMapper.createObjectNode();
        
        // 设置模型
        standardRequest.put("model", instance.getModelName());
        
        // 构建 messages 数组
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", oldRequest.getMessage());
        messages.add(userMessage);
        standardRequest.set("messages", messages);
        
        // 设置流式输出
        standardRequest.put("stream", true);
        
        // 设置幂等键
        if (oldRequest.getIdempotencyKey() != null) {
            standardRequest.put("idempotency_key", oldRequest.getIdempotencyKey());
        }
        
        return standardRequest;
    }
    
    /**
     * 根据实例配置构造下游请求体（保留向后兼容）
     * <p>
     * 优先使用数据库中配置的 `post_model` 模板，并支持占位符：<br>
     * - <code>$model</code>：替换为实例的模型名<br>
     * - <code>$messages</code>：替换为用户输入<br>
     * 若模板为空或无效，则退回到默认最小请求体（仅包含 message + stream）。
     *
     * @param instance    目标模型实例
     * @param userMessage 用户输入
     * @return 可直接序列化为 JSON 的请求体节点
     * @deprecated 使用 buildPayloadWithConverter 替代
     */
    @Deprecated
    public ObjectNode buildPayload(ModelInstance instance, String userMessage) {
        String template = instance.getPostModel();
        ObjectNode payload;

        if (template == null || template.isBlank()) {
            payload = objectMapper.createObjectNode();
            payload.put("stream", true);
            payload.put("message", userMessage);
        } else {
            payload = parseTemplate(template);
            replacePlaceholders(payload, instance.getModelName(), userMessage);
            if (!payload.has("stream")) {
                payload.put("stream", true);
            }
        }

        payload.put("model", instance.getModelName());
        return payload;
    }

    /**
     * 解析并深拷贝存储在数据库中的 JSON 模板
     * <p>
     * 若模板格式非法，则记录告警日志并返回空对象，交由上层填充默认字段。
     */
    private ObjectNode parseTemplate(String template) {
        try {
            JsonNode root = objectMapper.readTree(template);
            if (root != null && root.isObject()) {
                return (ObjectNode) root.deepCopy();
            }
        } catch (Exception e) {
            log.warn("Invalid post_model JSON template, fallback to default: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    /**
     * 递归替换 JSON 模板中的占位符
     *
     * @param node        模板根节点
     * @param model       模型名称
     * @param userMessage 用户输入
     * @return 是否至少替换过一次占位符
     */
    private boolean replacePlaceholders(JsonNode node, String model, String userMessage) {
        boolean replaced = false;
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            var fields = obj.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode child = entry.getValue();
                if (child.isTextual()) {
                    String text = child.asText();
                    if ("$model".equals(text)) {
                        obj.put(entry.getKey(), model);
                        replaced = true;
                    } else if ("$messages".equals(text)) {
                        obj.put(entry.getKey(), userMessage);
                        replaced = true;
                    }
                } else {
                    replaced = replacePlaceholders(child, model, userMessage) || replaced;
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    String text = child.asText();
                    if ("$model".equals(text)) {
                        array.set(i, TextNode.valueOf(model));
                        replaced = true;
                    } else if ("$messages".equals(text)) {
                        array.set(i, TextNode.valueOf(userMessage));
                        replaced = true;
                    }
                } else {
                    replaced = replacePlaceholders(child, model, userMessage) || replaced;
                }
            }
        }
        return replaced;
    }

    /**
     * 将下游 SSE 数据块转换为统一的输出格式（保留向后兼容）
     * <p>
     * - 当实例开启 raw 模式时，直接返回归一化后的原始行；<br>
     * - 否则按实例配置的路径提取 requestId / 内容 / 序号，包装为简单 JSON。<br>
     * 特殊标记 "[DONE]" 会原样透传，用于表示流结束。
     *
     * @param chunk            下游返回的原始片段（一段可能包含多行 SSE）
     * @param instance         对应的模型实例
     * @param defaultRequestId 当下游未返回 requestId 时使用的默认 ID（通常为幂等键）
     * @param seqCounter       本次请求级别的自增序号
     * @return 统一封装后的字符串列表
     * @deprecated 使用 SseResponseConverter 替代
     */
    @Deprecated
    private List<String> convertSseChunk(String chunk, ModelInstance instance, String defaultRequestId,
                                         AtomicInteger seqCounter) {
        return sseResponseConverter.convertSseChunk(chunk, instance, defaultRequestId, seqCounter);
    }

    /**
     * 规范化单行 SSE 数据，提取出有效 payload
     * <p>
     * 去掉前缀 <code>data:</code>，并只保留 JSON 对象或 "[DONE]" 标记，其它内容会被过滤。
     * <p>
     * 优化：减少字符串操作，避免多次 trim() 和 substring() 调用。
     */
    private String normalizeSsePayload(String line) {
        if (line == null) {
            return null;
        }
        // 先 trim 一次，后续直接操作索引避免重复 trim
        int start = 0;
        int end = line.length();
        // 去除前导空白
        while (start < end && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        // 去除尾随空白
        while (end > start && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return null;
        }
        // 去除 "data:" 前缀（可能有多个）
        while (end - start >= 5 && line.regionMatches(true, start, "data:", 0, 5)) {
            start += 5;
            // 去除前缀后的空白
            while (start < end && Character.isWhitespace(line.charAt(start))) {
                start++;
            }
        }
        if (start >= end) {
            return null;
        }
        // 检查是否为 JSON 对象或 [DONE] 标记
        char firstChar = line.charAt(start);
        char lastChar = line.charAt(end - 1);
        if (firstChar == '{' && lastChar == '}') {
            return line.substring(start, end);
        }
        if (end - start == 6 && line.regionMatches(true, start, "[DONE]", 0, 6)) {
            return "[DONE]";
        }
        return null;
    }

    /**
     * 在配置为空时返回默认 JSONPath 字符串
     */
    private String defaultPath(String path, String defaultPath) {
        if (path == null || path.isBlank()) {
            return defaultPath;
        }
        return path;
    }

    /**
     * 从 JSON 中按路径读取字符串值
     */
    private String readTextByPath(JsonNode root, String path) {
        JsonNode node = readNodeByPath(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.asText(null);
    }

    /**
     * 从 JSON 中按路径读取整型值，支持数字和可解析为数字的字符串
     */
    private Integer readIntByPath(JsonNode root, String path) {
        JsonNode node = readNodeByPath(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 简单的点分路径解析器，支持数组下标（如 <code>choices.0.delta.content</code>）
     */
    private JsonNode readNodeByPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                if (!current.isArray() || index >= current.size()) {
                    return null;
                }
                current = current.get(index);
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }
}
