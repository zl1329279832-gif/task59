-- ============================================================
-- 个性化学习推荐闭环 - 新增表结构
-- ============================================================

-- 推荐日志表：记录推荐系统的曝光、点击、屏蔽事件
CREATE TABLE IF NOT EXISTS `b_recommend_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     VARCHAR(64)  NOT NULL COMMENT '用户id',
    `thing_id`    VARCHAR(64)  NOT NULL COMMENT '资源id',
    `type`        VARCHAR(20)  NOT NULL COMMENT '事件类型: exposure(曝光), click(点击), block(屏蔽)',
    `reason`      VARCHAR(255) DEFAULT '' COMMENT '推荐原因',
    `create_time` VARCHAR(32)  DEFAULT NULL COMMENT '事件时间(毫秒时间戳)',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_thing_id` (`thing_id`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐日志表';

-- 推荐反馈表：记录用户对推荐结果的"不感兴趣"操作
CREATE TABLE IF NOT EXISTS `b_recommend_feedback` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          VARCHAR(64)  NOT NULL COMMENT '用户id',
    `thing_id`         VARCHAR(64)  NOT NULL COMMENT '资源id',
    `classification_id` BIGINT      DEFAULT NULL COMMENT '资源所属分类id（冗余，便于按分类降权）',
    `create_time`      VARCHAR(32)  DEFAULT NULL COMMENT '反馈时间(毫秒时间戳)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_thing` (`user_id`, `thing_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐反馈表（不感兴趣）';
