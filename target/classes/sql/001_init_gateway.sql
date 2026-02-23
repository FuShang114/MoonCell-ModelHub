-- MoonCell Gateway bootstrap schema
-- This script runs at application startup via spring.sql.init.
-- Note: target database must already exist (e.g. mooncell).

CREATE TABLE IF NOT EXISTS `provider` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `description` VARCHAR(255) DEFAULT NULL,
  `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `model_instance` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `provider_id` BIGINT NOT NULL,
  `model_name` VARCHAR(100) NOT NULL,
  `url` VARCHAR(500) NOT NULL,
  `api_key` VARCHAR(255) DEFAULT NULL,
  `post_model` TEXT,
  `response_request_id_path` VARCHAR(100) DEFAULT NULL,
  `response_content_path` VARCHAR(100) DEFAULT NULL,
  `response_seq_path` VARCHAR(100) DEFAULT NULL,
  `response_raw_enabled` TINYINT(1) NOT NULL DEFAULT 0,
  `weight` INT NOT NULL DEFAULT 10,
  `rpm_limit` INT NOT NULL DEFAULT 600,
  `tpm_limit` INT NOT NULL DEFAULT 600000,
  `max_qps` INT DEFAULT 10,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_instance_url` (`url`),
  KEY `idx_model_name` (`model_name`),
  KEY `idx_provider_id` (`provider_id`),
  CONSTRAINT `fk_model_instance_provider`
    FOREIGN KEY (`provider_id`) REFERENCES `provider` (`id`)
    ON UPDATE RESTRICT
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO `provider` (`name`, `description`) VALUES
  ('openai', 'OpenAI official models'),
  ('azure', 'Azure OpenAI Service'),
  ('ark', 'Volcengine Ark'),
  ('deepseek', 'DeepSeek');
