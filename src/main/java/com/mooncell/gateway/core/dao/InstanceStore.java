package com.mooncell.gateway.core.dao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooncell.gateway.core.converter.ConverterFactory;
import com.mooncell.gateway.core.converter.RequestConverter;
import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.dto.ProviderDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地实例/服务商存储。
 *
 * <p>使用内存作为真实读写路径，并在每次增删改或状态更新时立即将数据刷盘到本地 JSON 文件：
 * <ul>
 *     <li>应用启动时从磁盘恢复 providers 与 instances</li>
 *     <li>所有增删改以及健康状态更新作用于内存后，立即同步刷盘</li>
 *     <li>应用关闭前不再依赖额外的定时 flush 任务</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceStore {

    /**
     * 相对“应用根目录”的持久化目录。
     * <p>
     * 应用根目录的解析逻辑为：
     * <ul>
     *     <li>打包后运行（jar 模式）：使用 jar 所在的目录作为根目录</li>
     *     <li>开发模式（mvn spring-boot:run / IDE 运行）：
     *     若 codeSource 目录形如 {@code ${projectRoot}/target/classes}，
     *     则回退到 {@code ${projectRoot}} 作为根目录</li>
     *     <li>若上述逻辑解析失败，则退回到 {@code user.dir}（当前工作目录）</li>
     * </ul>
     * 这样可以保证：不论从哪个目录执行 {@code java -jar ...}，
     * 都始终以“项目根/可执行文件所在目录”为持久化起点，而不是绑定到某个盘符。
     */
    private static final String PERSISTENCE_DIR = "data/instances";
    private static final String PERSISTENCE_FILE = "instances.json";

    private final ObjectMapper objectMapper;
    private final ConverterFactory converterFactory;

    private final ConcurrentHashMap<Long, ProviderRecord> providersById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> providerNameToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ModelInstance> instancesById = new ConcurrentHashMap<>();

    private final AtomicLong providerSeq = new AtomicLong(0);
    private final AtomicLong instanceSeq = new AtomicLong(0);

    @PostConstruct
    public void init() {
        Path baseDir = resolveAppBaseDir();
        Path persistenceDir = baseDir.resolve(PERSISTENCE_DIR);
        log.info("InstanceStore baseDir={}, persistenceDir={}", baseDir.toAbsolutePath(), persistenceDir.toAbsolutePath());
        restoreFromDisk();
        log.info("InstanceStore initialized: providers={}, instances={}",
                providersById.size(), instancesById.size());
    }

    // ===== 对外查询接口 =====

    public List<ModelInstance> findAllInstances() {
        return new ArrayList<>(instancesById.values());
    }

    public List<ModelInstance> findByModelName(String modelName) {
        if (modelName == null) {
            return Collections.emptyList();
        }
        String target = modelName.trim();
        if (target.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModelInstance> result = new ArrayList<>();
        for (ModelInstance instance : instancesById.values()) {
            if (target.equals(instance.getModelName())) {
                result.add(instance);
            }
        }
        return result;
    }

    public ModelInstance findByUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        for (ModelInstance instance : instancesById.values()) {
            if (url.equals(instance.getUrl())) {
                return instance;
            }
        }
        return null;
    }

    public ModelInstance findById(Long id) {
        if (id == null) {
            return null;
        }
        return instancesById.get(id);
    }

    public List<ProviderDto> findAllProviders() {
        List<ProviderRecord> records = new ArrayList<>(providersById.values());
        // 与原数据库实现保持一致，按 id 倒序
        records.sort(Comparator.comparingLong(ProviderRecord::id).reversed());
        List<ProviderDto> result = new ArrayList<>(records.size());
        for (ProviderRecord r : records) {
            ProviderDto dto = new ProviderDto();
            dto.setId(r.id());
            dto.setName(r.name());
            dto.setDescription(r.description());
            dto.setRequestConversionRule(r.requestConversionRule());
            dto.setResponseConversionRule(r.responseConversionRule());
            result.add(dto);
        }
        return result;
    }

    public Long findProviderIdByName(String name) {
        if (name == null) {
            return null;
        }
        return providerNameToId.get(name.trim());
    }
    
    public ProviderRecord findProviderById(Long id) {
        if (id == null) {
            return null;
        }
        return providersById.get(id);
    }

    // ===== 对外写入接口（增删改）=====

    public synchronized long insertProvider(String name, String description) {
        return insertProvider(name, description, null, null);
    }
    
    public synchronized long insertProvider(String name, String description, 
                                           String requestConversionRule, String responseConversionRule) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("provider name must not be blank");
        }
        String key = name.trim();
        Long existingId = providerNameToId.get(key);
        if (existingId != null) {
            // 与数据库唯一约束保持语义一致：直接复用已有 ID
            return existingId;
        }
        long id = providerSeq.incrementAndGet();
        ProviderRecord record = new ProviderRecord(id, key, description, 
                                                  requestConversionRule, responseConversionRule);
        providersById.put(id, record);
        providerNameToId.put(key, id);
        flushImmediately();
        return id;
    }

    public synchronized boolean updateProvider(Long id, String name, String description) {
        return updateProvider(id, name, description, null, null);
    }
    
    public synchronized boolean updateProvider(Long id, String name, String description,
                                               String requestConversionRule, String responseConversionRule) {
        if (id == null) {
            return false;
        }
        ProviderRecord existing = providersById.get(id);
        if (existing == null) {
            return false;
        }
        String newName = name != null ? name.trim() : existing.name();
        if (newName.isEmpty()) {
            newName = existing.name();
        }
        // 更新 name 映射
        if (!newName.equals(existing.name())) {
            providerNameToId.remove(existing.name());
            if (providerNameToId.containsKey(newName)) {
                // 已存在同名 provider，保持简单策略：拒绝本次更新
                return false;
            }
            providerNameToId.put(newName, id);
        }
        ProviderRecord updated = new ProviderRecord(id, newName,
                description != null ? description : existing.description(),
                requestConversionRule != null ? requestConversionRule : existing.requestConversionRule(),
                responseConversionRule != null ? responseConversionRule : existing.responseConversionRule());
        providersById.put(id, updated);

        // 同步更新所有引用该 provider 的实例显示名，避免前端按 providerName 分组时不一致
        if (!newName.equals(existing.name())) {
            for (ModelInstance instance : instancesById.values()) {
                if (instance != null && instance.getProviderId() != null && instance.getProviderId().equals(id)) {
                    instance.setProviderName(newName);
                }
            }
        }

        flushImmediately();
        return true;
    }

    /**
     * 删除服务商：若存在关联实例则拒绝删除。
     *
     * @return "success" / "not_found" / "has_instances"
     */
    public synchronized String deleteProvider(Long id) {
        if (id == null) {
            return "not_found";
        }
        ProviderRecord existing = providersById.get(id);
        if (existing == null) {
            return "not_found";
        }
        boolean hasInstances = instancesById.values().stream()
                .anyMatch(inst -> inst != null && inst.getProviderId() != null && inst.getProviderId().equals(id));
        if (hasInstances) {
            return "has_instances";
        }
        providersById.remove(id);
        if (existing.name() != null) {
            providerNameToId.remove(existing.name());
        }
        flushImmediately();
        return "success";
    }

    public synchronized ModelInstance insertInstance(ModelInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        // URL 视为唯一键，若已存在则拒绝插入
        ModelInstance existing = findByUrl(instance.getUrl());
        if (existing != null) {
            throw new IllegalStateException("Instance with same url already exists: " + instance.getUrl());
        }
        long id = instanceSeq.incrementAndGet();
        instance.setId(id);
        // 补齐 providerName 便于展示
        if (instance.getProviderName() == null && instance.getProviderId() != null) {
            ProviderRecord provider = providersById.get(instance.getProviderId());
            if (provider != null) {
                instance.setProviderName(provider.name());
            }
        }
        // 初始化运行时状态与预绑定的请求转换器
        initializeInstanceRuntime(instance);

        instancesById.put(id, instance);
        flushImmediately();
        return instance;
    }

    public synchronized boolean updateInstance(ModelInstance updated) {
        if (updated == null || updated.getId() == null) {
            return false;
        }
        ModelInstance existing = instancesById.get(updated.getId());
        if (existing == null) {
            return false;
        }
        // 保留运行时状态字段，仅替换配置相关字段
        Long id = existing.getId();
        AtomicStateSnapshot runtime = AtomicStateSnapshot.from(existing);

        // 确保 providerName 一致
        if (updated.getProviderName() == null && updated.getProviderId() != null) {
            ProviderRecord provider = providersById.get(updated.getProviderId());
            if (provider != null) {
                updated.setProviderName(provider.name());
            }
        }
        updated.setId(id);
        // 恢复运行时状态
        runtime.applyTo(updated);
        // 重新初始化运行时转换相关状态
        initializeInstanceRuntime(updated);

        instancesById.put(id, updated);
        flushImmediately();
        return true;
    }

    public synchronized boolean updateStatus(Long id, Boolean isActive) {
        if (id == null) {
            return false;
        }
        ModelInstance existing = instancesById.get(id);
        if (existing == null) {
            return false;
        }
        existing.setIsActive(isActive);
        flushImmediately();
        return true;
    }

    public synchronized boolean deleteInstance(Long id) {
        if (id == null) {
            return false;
        }
        ModelInstance removed = instancesById.remove(id);
        if (removed != null) {
            flushImmediately();
            return true;
        }
        return false;
    }

    // ===== 持久化实现 =====

    /**
     * 对外写操作统一调用的即时刷盘入口。
     * <p>
     * 确保每次配置变更或健康状态更新后，尽快将内存状态落盘。
     */
    private void flushImmediately() {
        try {
            flushToDisk();
        } catch (Exception e) {
            log.error("Failed to flush InstanceStore to disk", e);
        }
    }

    private void flushToDisk() throws IOException {
        Path dir = resolvePersistenceDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        PersistedData data = new PersistedData();
        data.providerSeq = providerSeq.get();

        List<ProviderRecord> providerList = new ArrayList<>(providersById.values());
        providerList.sort(Comparator.comparingLong(ProviderRecord::id));
        for (ProviderRecord r : providerList) {
            PersistedProvider p = new PersistedProvider();
            p.id = r.id();
            p.name = r.name();
            p.description = r.description();
            p.requestConversionRule = r.requestConversionRule();
            p.responseConversionRule = r.responseConversionRule();
            data.providers.add(p);
        }

        data.instanceSeq = instanceSeq.get();
        List<ModelInstance> instanceList = new ArrayList<>(instancesById.values());
        instanceList.sort(Comparator.comparingLong(ModelInstance::getId));

        List<PersistedInstance> persistedInstances = new ArrayList<>(instanceList.size());
        for (ModelInstance inst : instanceList) {
            PersistedInstance p = new PersistedInstance();
            p.id = inst.getId();
            p.providerId = inst.getProviderId();
            p.providerName = inst.getProviderName();
            p.modelName = inst.getModelName();
            p.url = inst.getUrl();
            p.apiKey = inst.getApiKey();
            p.postModel = inst.getPostModel();
            p.responseRequestIdPath = inst.getResponseRequestIdPath();
            p.responseContentPath = inst.getResponseContentPath();
            p.responseSeqPath = inst.getResponseSeqPath();
            p.responseRawEnabled = inst.getResponseRawEnabled();
            p.requestConversionRule = inst.getRequestConversionRule();
            p.responseConversionRule = inst.getResponseConversionRule();
            p.weight = inst.getWeight();
            p.rpmLimit = inst.getRpmLimit();
            p.tpmLimit = inst.getTpmLimit();
            p.poolKey = inst.getPoolKey();
            p.maxQps = inst.getMaxQps();
            p.isActive = inst.getIsActive();
            persistedInstances.add(p);
        }
        data.instances = persistedInstances;

        Path file = dir.resolve(PERSISTENCE_FILE);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        log.debug("InstanceStore flushed to {}", file.toAbsolutePath());
    }

    private void restoreFromDisk() {
        try {
            Path dir = resolvePersistenceDir();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path file = dir.resolve(PERSISTENCE_FILE);
            if (!Files.exists(file)) {
                // Docker 场景下首次启动时文件可能不存在，这里主动创建一个空文件，完成一次“存在性校验”
                log.info("No persisted instance store found at {}, creating empty store file",
                        file.toAbsolutePath());
                PersistedData empty = new PersistedData();
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), empty);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                log.info("Persisted instance store file is empty, start with empty store");
                return;
            }
            PersistedData data = objectMapper.readValue(bytes, PersistedData.class);

            providersById.clear();
            providerNameToId.clear();
            instancesById.clear();

            if (data.providers != null) {
                for (PersistedProvider p : data.providers) {
                    ProviderRecord record = new ProviderRecord(p.id, p.name, p.description,
                                                              p.requestConversionRule, p.responseConversionRule);
                    providersById.put(p.id, record);
                    if (p.name != null && !p.name.isBlank()) {
                        providerNameToId.put(p.name.trim(), p.id);
                    }
                }
            }
            if (data.instances != null) {
                for (PersistedInstance p : data.instances) {
                    if (p.id == null) {
                        continue;
                    }
                    ModelInstance instance = new ModelInstance();
                    instance.setId(p.id);
                    instance.setProviderId(p.providerId);
                    instance.setProviderName(p.providerName);
                    instance.setModelName(p.modelName);
                    instance.setUrl(p.url);
                    instance.setApiKey(p.apiKey);
                    instance.setPostModel(p.postModel);
                    instance.setResponseRequestIdPath(p.responseRequestIdPath);
                    instance.setResponseContentPath(p.responseContentPath);
                    instance.setResponseSeqPath(p.responseSeqPath);
                    instance.setResponseRawEnabled(p.responseRawEnabled);
                    instance.setRequestConversionRule(p.requestConversionRule);
                    instance.setResponseConversionRule(p.responseConversionRule);
                    instance.setWeight(p.weight);
                    instance.setRpmLimit(p.rpmLimit);
                    instance.setTpmLimit(p.tpmLimit);
                    instance.setPoolKey(p.poolKey);
                    instance.setMaxQps(p.maxQps);
                    instance.setIsActive(p.isActive);

                    // 从磁盘恢复后，补齐运行时状态与预绑定转换器
                    initializeInstanceRuntime(instance);
                    instancesById.put(instance.getId(), instance);
                }
            }
            long maxProviderId = providersById.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
            long maxInstanceId = instancesById.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
            providerSeq.set(Math.max(maxProviderId, data.providerSeq));
            instanceSeq.set(Math.max(maxInstanceId, data.instanceSeq));

            log.info("InstanceStore restored from disk: providers={}, instances={}",
                    providersById.size(), instancesById.size());
        } catch (Exception e) {
            log.warn("Failed to restore InstanceStore from disk, start with empty store", e);
        }
    }

    /**
     * 解析应用根目录：
     * <ul>
     *     <li>若 codeSource 是一个 jar 文件，则使用 jar 所在目录</li>
     *     <li>若 codeSource 是 {@code .../target/classes}，则回退到项目根目录</li>
     *     <li>否则直接使用 codeSource 路径</li>
     * </ul>
     * 若解析过程中出现异常，则退回到 {@code user.dir}。
     */
    private static Path resolveAppBaseDir() {
        try {
            Path codeSourcePath = Paths.get(
                    InstanceStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            // jar 模式：codeSource 是一个文件
            if (Files.isRegularFile(codeSourcePath)) {
                return codeSourcePath.getParent();
            }
            // 开发模式：通常是 ${projectRoot}/target/classes/
            Path fileName = codeSourcePath.getFileName();
            if (fileName != null && "classes".equals(fileName.toString())) {
                Path targetDir = codeSourcePath.getParent();
                if (targetDir != null && "target".equals(targetDir.getFileName().toString())) {
                    Path projectRoot = targetDir.getParent();
                    if (projectRoot != null) {
                        return projectRoot;
                    }
                }
            }
            // 兜底：直接使用 codeSource 目录
            return codeSourcePath;
        } catch (URISyntaxException e) {
            // 兜底：使用当前工作目录
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        }
    }

    /**
     * 解析持久化目录：始终以应用根目录为起点的相对路径。
     */
    private static Path resolvePersistenceDir() {
        return resolveAppBaseDir().resolve(PERSISTENCE_DIR);
    }

    /**
     * 初始化实例的运行时状态与预绑定转换器。
     * <p>
     * - 确保 Atomic 字段已初始化；
     * - 基于实例当前配置选择并绑定请求转换器，便于负载均衡后直接调用实例的 convertRequest 方法。
     */
    private void initializeInstanceRuntime(ModelInstance instance) {
        if (instance == null) {
            return;
        }
        instance.ensureRuntimeState();
        try {
            RequestConverter requestConverter = converterFactory.getRequestConverter(instance);
            instance.setRequestConverter(requestConverter);
        } catch (Exception e) {
            // 初始化阶段如果绑定转换器失败，仅记录日志，保留后续调用时抛出的显式错误
            log.warn("Failed to bind request converter for instance {}: {}", instance.getId(), e.getMessage());
        }
    }

    // ===== 内部类型 =====

    public record ProviderRecord(long id, String name, String description, 
                                   String requestConversionRule, String responseConversionRule) {
    }

    /**
     * 仅用于在更新实例配置时暂存运行时状态，避免丢失统计信息。
     */
    private record AtomicStateSnapshot(
            Integer failureCount,
            Integer requestCount,
            Long totalLatency,
            Long lastUsedTime,
            Long lastFailureTime,
            Long lastHeartbeat,
            Boolean circuitOpen
    ) {
        static AtomicStateSnapshot from(ModelInstance instance) {
            if (instance == null) {
                return new AtomicStateSnapshot(0, 0, 0L, 0L, 0L, 0L, false);
            }
            instance.ensureRuntimeState();
            Integer failure = instance.getFailureCount() != null ? instance.getFailureCount().get() : 0;
            Integer request = instance.getRequestCount() != null ? instance.getRequestCount().get() : 0;
            Long latency = instance.getTotalLatency() != null ? instance.getTotalLatency().get() : 0L;
            Long lastUsed = instance.getLastUsedTime();
            Long lastFail = instance.getLastFailureTime();
            Long lastHb = instance.getLastHeartbeat();
            Boolean open = instance.isCircuitOpen();
            return new AtomicStateSnapshot(failure, request, latency, lastUsed, lastFail, lastHb, open);
        }

        void applyTo(ModelInstance target) {
            if (target == null) {
                return;
            }
            target.ensureRuntimeState();
            if (target.getFailureCount() != null && failureCount != null) {
                target.getFailureCount().set(failureCount);
            }
            if (target.getRequestCount() != null && requestCount != null) {
                target.getRequestCount().set(requestCount);
            }
            if (target.getTotalLatency() != null && totalLatency != null) {
                target.getTotalLatency().set(totalLatency);
            }
            if (lastUsedTime != null) {
                target.setLastUsedTime(lastUsedTime);
            }
            if (lastFailureTime != null) {
                target.setLastFailureTime(lastFailureTime);
            }
            if (lastHeartbeat != null) {
                target.setLastHeartbeat(lastHeartbeat);
            }
            if (circuitOpen != null) {
                target.setCircuitOpen(circuitOpen);
            }
        }
    }

    private static class PersistedProvider {
        public long id;
        public String name;
        public String description;
        public String requestConversionRule;
        public String responseConversionRule;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PersistedInstance {
        public Long id;
        public Long providerId;
        public String providerName;
        public String modelName;
        public String url;
        public String apiKey;
        public String postModel;
        public String responseRequestIdPath;
        public String responseContentPath;
        public String responseSeqPath;
        public Boolean responseRawEnabled;
        public String requestConversionRule;
        public String responseConversionRule;
        public Integer weight;
        public Integer rpmLimit;
        public Integer tpmLimit;
        public String poolKey;
        public Integer maxQps;
        public Boolean isActive;
    }

    private static class PersistedData {
        public long providerSeq;
        public long instanceSeq;
        public List<PersistedProvider> providers = new ArrayList<>();
        public List<PersistedInstance> instances = new ArrayList<>();
    }
}
