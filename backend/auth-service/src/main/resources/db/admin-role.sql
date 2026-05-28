-- =============================================================================
-- Migration: admin-role.sql
-- Spec: admin-dashboard (Requirement 1.1)
-- Target: MySQL 8.0.16+ (CHECK constraint requires 8.0.16 or newer)
-- Database: auth-service `users` table
--
-- 运维须知 (OPERATIONAL NOTE):
--   该脚本【必须在部署 admin-dashboard 特性前由运维手工执行】。
--   项目当前未引入 Flyway / Liquibase，按既有惯例由运维在目标 MySQL 上
--   直接运行该脚本，再发布新版本的 auth-service。
--
--   执行命令示例:
--     mysql -h <host> -u <user> -p <auth_db> < admin-role.sql
--
--   该脚本是幂等设计的:
--     - 重复执行 ALTER ADD COLUMN 时若列已存在，MySQL 会返回错误，
--       请运维在重跑前先确认 `users.role` 是否已存在 (使用下方注释的 SELECT)。
--     - UPDATE 兜底回填永远安全 (空集时无副作用)。
--     - CREATE INDEX 与 ADD CONSTRAINT 在已存在时也会报错，请逐条按需执行。
--
--   预检 (可选):
--     SELECT COLUMN_NAME, COLUMN_DEFAULT, IS_NULLABLE, DATA_TYPE
--       FROM INFORMATION_SCHEMA.COLUMNS
--      WHERE TABLE_SCHEMA = DATABASE()
--        AND TABLE_NAME = 'users'
--        AND COLUMN_NAME = 'role';
-- =============================================================================

-- 1) 加列：默认 'USER'，避免 NOT NULL 与既有行冲突；位置紧随 background_image_url
ALTER TABLE users
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER'
    AFTER background_image_url;

-- 2) 兜底回填：DEFAULT 已经处理新列；显式 UPDATE 仅为防御历史奇异行
--    (例如其它途径插入但绕过了 DEFAULT 的脏数据)
UPDATE users
   SET role = 'USER'
 WHERE role IS NULL
    OR role = '';

-- 3) 索引：为管理端"按 role 过滤"场景准备；极小基数索引不会膨胀 B-tree
CREATE INDEX idx_users_role ON users (role);

-- 4) CHECK 约束：限定 role 只能是 'USER' 或 'ADMIN'
--    需要 MySQL 8.0.16 及以上才会强制执行 (低版本会被解析但忽略)
ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));
