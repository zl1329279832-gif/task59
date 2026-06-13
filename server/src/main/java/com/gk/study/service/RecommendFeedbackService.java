package com.gk.study.service;

import java.util.List;

public interface RecommendFeedbackService {
    void block(String userId, String thingId, Long classificationId);
    List<String> getBlockedThingIds(String userId);
    List<Long> getBlockedClassificationIds(String userId);
    boolean isBlocked(String userId, String thingId);
}
