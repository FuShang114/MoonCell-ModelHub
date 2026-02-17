package com.mooncell.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mooncell.gateway.api.OpenAiRequest;
import com.mooncell.gateway.core.balancer.LoadBalancer;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayService {
    private static final String IDEMPOTENCY_KEY_PREFIX = "mooncell:idempotency:";
    private static final long IDEMPOTENCY_TTL_SECONDS = 300L; // 5 分钟

    // Lua：如果 key 已存在，返回 0；否则设置并返回 1
    private static final DefaultRedisScript<Long> IDEMPOTENCY_SCRIPT;
    static {
        IDEMPOTENCY_SCRIPT = new DefaultRedisScript<>();
        IDEMPOTENCY_SCRIPT.setResultType(Long.class);
        IDEMPOTENCY_SCRIPT.setScriptText(
                "local v = redis.call('EXISTS', KEYS[1]) \n" +
                "if v == 1 then \n" +
                "  return 0 \n" +
                "else \n" +
                "  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) \n" +
                "  return 1 \n" +
                "end"
        );
    }

    private final LoadBalancer loadBalancer;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public Flux<String> chat(OpenAiRequest request) {
        String rawMessage = request.getMessage();
        if (rawMessage == null || rawMessage.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be empty");
        }

        // 1. 幂等控制（Redis + Lua）
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            idempotencyKey = UUID.randomUUID().toString();
        }
        String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;

        final String finalIdempotencyKey = idempotencyKey;
        log.info("Received request, idempotencyKey={}", finalIdempotencyKey);

        Long ok;
        try {
            ok = stringRedisTemplate.execute(
                    IDEMPOTENCY_SCRIPT,
                    Collections.singletonList(redisKey),
                    "1",
                    String.valueOf(IDEMPOTENCY_TTL_SECONDS)
            );
        } catch (DataAccessException e) {
            log.error("Idempotency check failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Idempotency check failed");
        }

        if (ok == null || ok == 0L) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate request");
        }

        // 2. 从负载均衡获取一个可用实例（整合RPM/TPM限流）
        int estimatedTokens = estimateTotalTokens(rawMessage);
        LoadBalancer.InstanceLease lease = loadBalancer.acquireLease(estimatedTokens);
        ModelInstance instance = lease != null ? lease.getInstance() : null;

        if (instance == null) {
            stringRedisTemplate.delete(redisKey);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No available instances or rate limit exceeded");
        }

        String targetUrl = buildTargetUrl(instance);

        // 3. 构造下游请求体
        ObjectNode payload = buildPayload(instance, rawMessage);

        WebClient client = webClientBuilder.build();
        ModelInstance finalInstance = instance;
        String leaseId = lease != null ? lease.getLeaseId() : null;

        AtomicInteger seqCounter = new AtomicInteger(0);

        // 4. 转换下游 SSE 为统一输出格式
        return client.post()
                .uri(targetUrl)
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
                        convertSseChunk(chunk, finalInstance, finalIdempotencyKey, seqCounter)
                ))
                .doOnError(e -> {
                    log.error("Downstream error for key {}", finalIdempotencyKey, e);
                    finalInstance.recordFailure();
                })
                .doOnComplete(() -> finalInstance.recordSuccess(0))
                .doFinally(signalType -> {
                    // 异常安全的资源释放
                    if (leaseId != null) {
                        loadBalancer.releaseLease(leaseId);
                    } else {
                        loadBalancer.releaseInstance(finalInstance);
                    }
                    stringRedisTemplate.delete(redisKey);
                });
    }
    
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

    public ObjectNode buildPayload(ModelInstance instance, String userMessage) {
        ArrayNode messages = buildDefaultMessages(userMessage);
        String template = instance.getPostModel();
        ObjectNode payload;

        if (template == null || template.isBlank()) {
            payload = objectMapper.createObjectNode();
            payload.put("stream", true);
            payload.set("messages", messages);
        } else {
            payload = parseTemplate(template);
            replacePlaceholders(payload, instance.getModelName(), messages);
            if (!payload.has("stream")) {
                payload.put("stream", true);
            }
        }

        payload.put("model", instance.getModelName());
        return payload;
    }

    public String buildTargetUrl(ModelInstance instance) {
        String base = instance.getUrl();
        // 只有 OpenAI 官方接口自动补齐 /chat/completions，其它服务商认为 URL 已经是完整路径
        if ("openai".equalsIgnoreCase(instance.getProviderName())
                && !base.endsWith("/chat/completions")) {
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/chat/completions";
        }
        return base;
    }

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

    private ArrayNode buildDefaultMessages(String userMessage) {
        ArrayNode outerArray = objectMapper.createArrayNode();
        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "user");
        messageNode.put("content", userMessage);
        outerArray.add(messageNode);
        return outerArray;
    }

    private boolean replacePlaceholders(JsonNode node, String model, ArrayNode messages) {
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
                        obj.set(entry.getKey(), messages.deepCopy());
                        replaced = true;
                    }
                } else {
                    replaced = replacePlaceholders(child, model, messages) || replaced;
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
                        array.set(i, messages.deepCopy());
                        replaced = true;
                    }
                } else {
                    replaced = replacePlaceholders(child, model, messages) || replaced;
                }
            }
        }
        return replaced;
    }

    private List<String> convertSseChunk(String chunk, ModelInstance instance, String defaultRequestId,
                                         AtomicInteger seqCounter) {
        List<String> outputs = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return outputs;
        }
        if (Boolean.TRUE.equals(instance.getResponseRawEnabled())) {
            String[] lines = chunk.split("\n");
            for (String line : lines) {
                String payload = normalizeSsePayload(line);
                if (payload != null) {
                    outputs.add(payload);
                }
            }
            return outputs;
        }
        String[] lines = chunk.split("\n");
        for (String line : lines) {
            String payload = normalizeSsePayload(line);
            if (payload == null) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                outputs.add("[DONE]");
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(payload);
                String requestIdPath = defaultPath(instance.getResponseRequestIdPath(), "id");
                String contentPath = defaultPath(instance.getResponseContentPath(), "choices.0.delta.content");
                String seqPath = defaultPath(instance.getResponseSeqPath(), "choices.0.index");

                String requestId = readTextByPath(root, requestIdPath);
                if (requestId == null || requestId.isBlank()) {
                    requestId = defaultRequestId;
                }

                String content = readTextByPath(root, contentPath);
                if (content == null) {
                    content = "";
                }

                Integer seq = readIntByPath(root, seqPath);
                if (seq == null) {
                    seq = seqCounter.incrementAndGet();
                }

                ObjectNode output = objectMapper.createObjectNode();
                output.put("requestId", requestId);
                output.put("content", content);
                output.put("seq", seq);
                outputs.add(objectMapper.writeValueAsString(output));
            } catch (Exception e) {
                log.debug("Failed to parse SSE data chunk: {}", e.getMessage());
            }
        }
        return outputs;
    }

    private String normalizeSsePayload(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        while (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring(5).trim();
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        if ("[DONE]".equals(trimmed)) {
            return trimmed;
        }
        return null;
    }

    private String defaultPath(String path, String defaultPath) {
        if (path == null || path.isBlank()) {
            return defaultPath;
        }
        return path;
    }

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
