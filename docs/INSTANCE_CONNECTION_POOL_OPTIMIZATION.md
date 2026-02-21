# 实例专用连接池优化

## 优化概述

将全局 WebClient 连接池改为**每个实例专用的连接池**，实现更精准的连接复用和资源管理。

## 优化背景

### 原有架构
- 使用全局 WebClient，所有实例共享一个连接池
- 连接池大小固定（500 连接）
- 无法为不同实例配置不同的超时和连接数

### 问题
1. **连接竞争**：所有实例共享连接池，可能导致连接竞争
2. **配置不灵活**：无法为不同实例设置不同的连接池大小
3. **资源浪费**：实例删除后，连接池资源无法及时释放

## 优化方案

### 核心思路
- **实例级连接池**：每个实例维护独立的 WebClient 和连接池
- **动态配置**：根据实例的 RPM 限制动态计算连接池大小
- **自动清理**：实例删除时自动释放连接池资源

### 实现细节

#### 1. InstanceWebClientManager 服务

创建了 `InstanceWebClientManager` 服务，负责：
- 为每个实例创建和维护专用的 WebClient
- 根据实例的 RPM 限制动态计算连接池大小
- 在实例删除时自动清理连接池资源

**连接池大小计算公式**：
```java
连接数 = max(10, min(200, rpmLimit / 10))
```

例如：
- `rpmLimit=600` → 连接数=60
- `rpmLimit=100` → 连接数=10
- `rpmLimit=3000` → 连接数=200（上限）

#### 2. GatewayService 更新

修改 `GatewayService`，使用实例专用的 WebClient：
```java
// 旧代码
return webClient.post()
    .uri(finalInstance.getUrl())
    ...

// 新代码
WebClient instanceWebClient = instanceWebClientManager.getWebClient(finalInstance);
return instanceWebClient.post()
    .uri(finalInstance.getUrl())
    ...
```

#### 3. LoadBalancer 集成

在 `LoadBalancer` 刷新实例时，通知 `InstanceWebClientManager` 清理不再存在的实例连接池：
```java
// 收集所有活跃实例的 ID
Set<Long> activeInstanceIds = instances.stream()
    .map(ModelInstance::getId)
    .filter(id -> id != null)
    .collect(Collectors.toSet());
    
// 通知 InstanceWebClientManager 清理不再存在的实例连接池
instanceWebClientManager.refreshInstances(activeInstanceIds);
```

#### 4. HeartbeatService 更新

`HeartbeatService` 也更新为使用实例专用的 WebClient，保持一致性。

#### 5. WebConfig 简化

移除了全局 WebClient Bean，因为现在每个实例都有自己的连接池。

## 优化收益

### 1. 连接复用更精准
- ✅ 每个实例的连接只用于该实例，避免连接竞争
- ✅ 连接池大小根据实例负载动态调整，更合理

### 2. 配置更灵活
- ✅ 可以为不同实例设置不同的连接池大小
- ✅ 可以根据实例的 RPM 限制自动调整连接数

### 3. 资源管理更高效
- ✅ 实例删除时立即释放连接池资源
- ✅ 避免资源泄漏和浪费

### 4. 性能提升
- ✅ 减少连接竞争，提升并发性能
- ✅ 连接池大小更合理，减少连接等待时间

## 代码变更

### 新增文件
- `src/main/java/com/mooncell/gateway/service/InstanceWebClientManager.java`

### 修改文件
- `src/main/java/com/mooncell/gateway/service/GatewayService.java`
- `src/main/java/com/mooncell/gateway/service/HeartbeatService.java`
- `src/main/java/com/mooncell/gateway/core/balancer/LoadBalancer.java`
- `src/main/java/com/mooncell/gateway/config/WebConfig.java`

## 配置说明

### 连接池参数

每个实例的连接池配置：
- **最大连接数**：根据 RPM 限制动态计算（10-200）
- **空闲连接保持时间**：20秒
- **连接最大生命周期**：10分钟
- **连接超时**：5秒
- **读取/写入超时**：60秒

### 连接池大小计算

```java
int rpmLimit = instance.getEffectiveRpmLimit();
int maxConnections = Math.max(10, Math.min(200, rpmLimit / 10));
```

## 监控建议

### 关键指标

1. **连接池使用率**
   - 每个实例的连接池使用情况
   - 连接等待时间

2. **实例连接池数量**
   - 当前管理的实例数量
   - 连接池创建和销毁频率

3. **性能指标**
   - 连接建立时间
   - 请求延迟
   - 吞吐量

### 监控方法

可以通过 `InstanceWebClientManager.getInstanceCount()` 获取当前管理的实例数量。

## 注意事项

1. **连接池大小**：根据实际负载调整连接池大小计算公式
2. **资源清理**：确保实例删除时连接池资源被正确释放
3. **监控告警**：设置连接池使用率告警，避免连接池耗尽

## 后续优化

1. **连接预热**：启动时预热连接池，减少首次请求延迟
2. **动态调整**：根据实际使用情况动态调整连接池大小
3. **连接池监控**：添加更详细的连接池监控指标

## 回滚方案

如果优化后出现问题，可以快速回滚：

1. **恢复全局 WebClient**：在 `WebConfig` 中恢复全局 WebClient Bean
2. **恢复 GatewayService**：使用全局 WebClient 替代实例专用 WebClient
3. **移除 InstanceWebClientManager**：删除新增的服务类

## 总结

通过为每个实例创建专用的连接池，实现了：
- ✅ 更精准的连接复用
- ✅ 更灵活的配置
- ✅ 更高效的资源管理
- ✅ 更好的性能表现

这是一个**读多写少**场景的典型优化：实例配置变化不频繁，但请求非常频繁，因此为每个实例维护专用连接池可以显著提升性能。
