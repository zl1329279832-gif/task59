-- H2 兼容模式的建表语句（用于测试）

CREATE TABLE IF NOT EXISTS `b_thing` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `title`            VARCHAR(255) DEFAULT NULL,
    `cover`            VARCHAR(255) DEFAULT NULL,
    `description`      TEXT,
    `price`            VARCHAR(64)  DEFAULT NULL,
    `status`           VARCHAR(20)  DEFAULT '0',
    `create_time`      VARCHAR(32)  DEFAULT NULL,
    `score`            VARCHAR(32)  DEFAULT '0',
    `address`          VARCHAR(255) DEFAULT NULL,
    `wxts`             VARCHAR(512) DEFAULT NULL,
    `yysj`             VARCHAR(255) DEFAULT NULL,
    `pv`               VARCHAR(32)  DEFAULT '0',
    `rate`             VARCHAR(32)  DEFAULT '0',
    `recommend_count`  VARCHAR(32)  DEFAULT '0',
    `wish_count`       VARCHAR(32)  DEFAULT '0',
    `collect_count`    VARCHAR(32)  DEFAULT '0',
    `classification_id` BIGINT      DEFAULT NULL,
    `user_id`          VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_thing_collect` (
    `id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `thing_id` VARCHAR(64)  DEFAULT NULL,
    `user_id`  VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_thing_wish` (
    `id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `thing_id` VARCHAR(64)  DEFAULT NULL,
    `user_id`  VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_record` (
    `id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `thing_id` BIGINT       DEFAULT NULL,
    `score`    INT          DEFAULT 0,
    `ip`       VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_order` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `status`          VARCHAR(20)  DEFAULT '1',
    `order_time`      VARCHAR(32)  DEFAULT NULL,
    `pay_time`        VARCHAR(32)  DEFAULT NULL,
    `thing_id`        VARCHAR(64)  DEFAULT NULL,
    `user_id`         VARCHAR(64)  DEFAULT NULL,
    `count`           VARCHAR(32)  DEFAULT NULL,
    `order_number`    VARCHAR(64)  DEFAULT NULL,
    `receiver_time`   VARCHAR(64)  DEFAULT NULL,
    `receiver_name`   VARCHAR(64)  DEFAULT NULL,
    `receiver_phone`  VARCHAR(64)  DEFAULT NULL,
    `remark`          VARCHAR(512) DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_comment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `content`     TEXT,
    `comment_time` VARCHAR(32) DEFAULT NULL,
    `like_count`  VARCHAR(32)  DEFAULT '0',
    `user_id`     VARCHAR(64)  DEFAULT NULL,
    `thing_id`    VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_classification` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `title`       VARCHAR(255) DEFAULT NULL,
    `create_time` VARCHAR(32)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(64)  DEFAULT NULL,
    `password`    VARCHAR(128) DEFAULT NULL,
    `nickname`    VARCHAR(64)  DEFAULT NULL,
    `mobile`      VARCHAR(32)  DEFAULT NULL,
    `email`       VARCHAR(128) DEFAULT NULL,
    `description` VARCHAR(512) DEFAULT NULL,
    `role`        VARCHAR(10)  DEFAULT '1',
    `status`      VARCHAR(10)  DEFAULT '1',
    `score`       VARCHAR(32)  DEFAULT '0',
    `avatar`      VARCHAR(255) DEFAULT NULL,
    `token`       VARCHAR(128) DEFAULT NULL,
    `create_time` VARCHAR(32)  DEFAULT NULL,
    `push_email`  VARCHAR(128) DEFAULT NULL,
    `push_switch` VARCHAR(10)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_recommend_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     VARCHAR(64)  DEFAULT NULL,
    `thing_id`    VARCHAR(64)  DEFAULT NULL,
    `type`        VARCHAR(20)  DEFAULT NULL,
    `reason`      VARCHAR(255) DEFAULT '',
    `create_time` VARCHAR(32)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `b_recommend_feedback` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`           VARCHAR(64)  DEFAULT NULL,
    `thing_id`          VARCHAR(64)  DEFAULT NULL,
    `classification_id` BIGINT       DEFAULT NULL,
    `create_time`       VARCHAR(32)  DEFAULT NULL,
    PRIMARY KEY (`id`)
);
