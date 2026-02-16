-- --------------------------------------------------------
-- 主机:                           127.0.0.1
-- 服务器版本:                        8.0.44 - MySQL Community Server - GPL
-- 服务器操作系统:                      Win64
-- HeidiSQL 版本:                  12.15.0.7171
-- --------------------------------------------------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;


-- 导出 mooncell 的数据库结构
CREATE DATABASE IF NOT EXISTS `mooncell` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `mooncell`;

-- 导出  表 mooncell.chat_task 结构
CREATE TABLE IF NOT EXISTS `chat_task` (
  `id` varchar(64) NOT NULL,
  `idempotency_key` varchar(128) DEFAULT NULL,
  `model` varchar(100) NOT NULL,
  `request_json` text NOT NULL,
  `status` varchar(20) NOT NULL,
  `retry_count` int DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idempotency_key` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 正在导出表  mooncell.chat_task 的数据：~0 rows (大约)

-- 导出  表 mooncell.crawler_task 结构
CREATE TABLE IF NOT EXISTS `crawler_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `task_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
  `target_url` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标URL',
  `task_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LEARNING' COMMENT '任务状态',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT '任务描述',
  `crawler_config` text COLLATE utf8mb4_unicode_ci COMMENT '爬虫配置JSON',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除 0:未删除 1:已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_name` (`task_name`),
  KEY `idx_task_status` (`task_status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='爬虫任务表';

-- 正在导出表  mooncell.crawler_task 的数据：~0 rows (大约)

-- 导出  表 mooncell.model_instance 结构
CREATE TABLE IF NOT EXISTS `model_instance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_id` bigint NOT NULL,
  `model_name` varchar(100) NOT NULL,
  `url` varchar(500) NOT NULL,
  `weight` int DEFAULT '10',
  `is_active` tinyint(1) DEFAULT '1',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `max_qps` varchar(100) NOT NULL,
  `post_model` text NOT NULL,
  `api_key` varchar(100) DEFAULT NULL,
  `response_request_id_path` varchar(100) DEFAULT NULL,
  `response_content_path` varchar(100) DEFAULT NULL,
  `response_seq_path` varchar(100) DEFAULT NULL,
  `response_raw_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `rpm_limit` int DEFAULT '600' COMMENT '每分钟请求上限',
  `tpm_limit` int DEFAULT '600000' COMMENT '每分钟Token上限',
  PRIMARY KEY (`id`),
  UNIQUE KEY `url` (`url`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 正在导出表  mooncell.model_instance 的数据：~1 rows (大约)
INSERT INTO `model_instance` (`id`, `provider_id`, `model_name`, `url`, `weight`, `is_active`, `created_at`, `updated_at`, `max_qps`, `post_model`, `api_key`, `response_request_id_path`, `response_content_path`, `response_seq_path`, `response_raw_enabled`, `rpm_limit`, `tpm_limit`) VALUES
	(1, 1, 'doubao-seed-1-6', 'https://ark.cn-beijing.volces.com/api/v3/responses', 10, 1, '2026-02-05 05:00:40', '2026-02-05 05:00:40', '5', '{\n  "input": "$messages",\n  "model": "$model",\n  "stream": true\n}', '7f1b1575-fb27-4230-a637-2316117d2cb8', 'item_id', 'delta', 'sequence_number', 0, 600, 600000);

-- 导出  表 mooncell.provider 结构
CREATE TABLE IF NOT EXISTS `provider` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 正在导出表  mooncell.provider 的数据：~1 rows (大约)
INSERT INTO `provider` (`id`, `name`, `description`, `created_at`) VALUES
	(1, 'ByteDance', '字节跳动', '2026-02-05 04:39:41');

-- 导出  表 mooncell.user 结构
CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码',
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `avatar_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像URL',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '用户状态 0:正常 1:禁用',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除 0:未删除 1:已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 正在导出表  mooncell.user 的数据：~1 rows (大约)
INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `avatar_url`, `status`, `created_at`, `updated_at`, `deleted`) VALUES
	(1, 'fushang', '$2a$10$BKsPajENYkkiH2oJjAvu8O6pwq9PPLZJxKMCbh4rjis9tGIRKxGRC', NULL, NULL, NULL, 0, '2026-02-06 16:25:46', '2026-02-06 16:25:46', 0);

/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;
