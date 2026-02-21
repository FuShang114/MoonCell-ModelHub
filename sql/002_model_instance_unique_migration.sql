-- 目的：
-- 允许“同 URL 不同 key/模型”的多实例并存，避免运行时扩容新增失败。
-- 旧约束通常是 model_instance.url 唯一，本脚本会删除该唯一约束，
-- 并改为更符合网关语义的组合唯一键（provider_id, model_name, url, api_key）。

SET @old_idx = (
    SELECT s.INDEX_NAME
    FROM INFORMATION_SCHEMA.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'model_instance'
      AND s.NON_UNIQUE = 0
      AND s.COLUMN_NAME = 'url'
    ORDER BY s.INDEX_NAME
    LIMIT 1
);

SET @drop_sql = IF(
    @old_idx IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE model_instance DROP INDEX `', @old_idx, '`')
);

PREPARE stmt_drop FROM @drop_sql;
EXECUTE stmt_drop;
DEALLOCATE PREPARE stmt_drop;

SET @new_idx_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'model_instance'
      AND s.INDEX_NAME = 'uk_model_instance_identity'
);

SET @add_sql = IF(
    @new_idx_exists > 0,
    'SELECT 1',
    'ALTER TABLE model_instance ADD UNIQUE KEY uk_model_instance_identity (provider_id, model_name, url, api_key)'
);

PREPARE stmt_add FROM @add_sql;
EXECUTE stmt_add;
DEALLOCATE PREPARE stmt_add;
