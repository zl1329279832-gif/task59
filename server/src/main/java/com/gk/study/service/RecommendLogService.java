package com.gk.study.service;

import java.util.List;
import java.util.Map;

public interface RecommendLogService {
    void log(String userId, String thingId, String type, String reason);
    List<Map<String, Object>> getStats();
    int countByType(String type);
    boolean hasClick(String userId, String thingId);
}
