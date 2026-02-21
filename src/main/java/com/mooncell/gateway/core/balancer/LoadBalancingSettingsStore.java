package com.mooncell.gateway.core.balancer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadBalancingSettingsStore {
    private final ObjectMapper objectMapper;

    private Path settingsPath() {
        return Path.of("data", "load-balancing-settings.json");
    }

    public Optional<LoadBalancingSettings> load() {
        Path path = settingsPath();
        try {
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return Optional.empty();
            }
            LoadBalancingSettings loaded = objectMapper.readValue(bytes, LoadBalancingSettings.class);
            return Optional.ofNullable(loaded);
        } catch (Exception e) {
            log.warn("Failed to load load-balancing settings from {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean save(LoadBalancingSettings settings) {
        if (settings == null) {
            return false;
        }
        Path path = settingsPath();
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            Files.write(path, bytes);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save load-balancing settings to {}: {}", path, e.getMessage());
            return false;
        }
    }
}

