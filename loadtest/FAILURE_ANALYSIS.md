# 失败原因分析报告

## 问题现象

1. **客户端压测脚本**：记录到失败请求（失败率约25-45%）
2. **服务端监控**：显示成功率100%，失败率0%
3. **策略拒绝计数**：队列满、预算、采样拒绝均为0

## 根本原因

### 1. 客户端超时（TIMEOUT）

**问题**：压测脚本设置了30秒超时（`AbortSignal.timeout(30000)`），但服务端没有超时配置。

**证据**：
- 客户端在30秒后超时，标记请求为失败
- 服务端仍在处理请求（可能下游服务响应慢）
- 服务端监控在请求完成后才记录成功，但客户端已经超时

**代码位置**：
- `loadtest/run-compare-data-distribution.mjs:203` - 客户端30秒超时
- `src/main/java/com/mooncell/gateway/service/GatewayService.java:90-111` - 服务端无超时配置

### 2. WebClient 缺少超时配置

**问题**：`WebClient` 没有配置连接超时、读取超时等参数。

**代码位置**：
- `src/main/java/com/mooncell/gateway/config/WebConfig.java:11-12` - WebClient.Builder 无超时配置
- `src/main/java/com/mooncell/gateway/service/GatewayService.java:84` - 直接使用 builder.build()

### 3. 下游服务响应慢

**可能原因**：
- 下游AI服务响应时间超过30秒
- 网络延迟
- 服务端负载高

## 失败模式分析

根据压测数据（`batch-006/TRADITIONAL-homogeneous-rps4`）：

1. **失败率趋势**：从25%逐渐上升到45%
2. **失败时间分布**：持续失败，非偶发
3. **失败类型**：主要是 `TIMEOUT`（客户端30秒超时）

## 解决方案

### 方案1：增加服务端超时配置（推荐）

在 `WebConfig.java` 中为 `WebClient` 配置超时：

```java
@Bean
public WebClient.Builder webClientBuilder() {
    HttpClient httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
        .responseTimeout(Duration.ofSeconds(30))
        .doOnConnected(conn -> 
            conn.addHandlerLast(new ReadTimeoutHandler(30))
                .addHandlerLast(new WriteTimeoutHandler(30))
        );
    
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(httpClient));
}
```

**优点**：
- 服务端主动超时，避免资源浪费
- 客户端和服务端超时一致，减少不一致

### 方案2：增加客户端超时时间

将客户端超时从30秒增加到60秒或更长：

```javascript
signal: AbortSignal.timeout(60000), // 60秒
```

**缺点**：
- 只是延迟了超时，不解决根本问题
- 如果下游服务真的慢，仍然会超时

### 方案3：异步处理 + 轮询

改为异步提交请求，客户端轮询结果：

**优点**：
- 避免长时间连接
- 更好的用户体验

**缺点**：
- 需要大幅改动架构
- 增加复杂度

## 建议

**立即执行**：方案1 - 增加服务端超时配置

**原因**：
1. 服务端应该主动控制超时，而不是依赖客户端
2. 避免资源浪费（长时间等待的请求）
3. 与客户端超时保持一致，减少不一致

## 监控建议

1. **记录请求延迟分布**：识别哪些请求接近超时
2. **记录下游服务响应时间**：定位慢请求的来源
3. **区分客户端超时和服务端超时**：在监控中分别统计
