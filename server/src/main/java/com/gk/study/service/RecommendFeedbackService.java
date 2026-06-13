package com.gk.study.service;

import com.gk.study.entity.RecommendFeedback;

import java.util.List;

public interface RecommendFeedbackService {

    /**
     * 用户对某资源标记"不感兴趣"
     */
    void block(String userId, String thingId, Long classificationId);

    /**
     * 获取用户所有屏蔽的资源id
     */
    List<String> getBlockedThingIds(String userId);

    /**
     * 获取用户所有屏蔽的分类id
     */
    List<Long> getBlockedClassificationIds(String userId);

    /**
     * 检查用户是否已屏蔽某资源
     */
    boolean isBlocked(String userId, String thingId);
}
