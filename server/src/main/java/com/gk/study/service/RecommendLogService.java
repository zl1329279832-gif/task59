package com.gk.study.service;

import com.gk.study.entity.RecommendLog;

import java.util.List;
import java.util.Map;

public interface RecommendLogService {

    /**
     * 记录推荐事件（曝光/点击/屏蔽）
     */
    void log(String userId, String thingId, String type, String reason);

    /**
     * 获取推荐统计（按事件类型分组计数）
     */
    List<Map<String, Object>> getStats();

    /**
     * 按类型统计数量
     */
    int countByType(String type);

    /**
     * 获取用户对某资源是否有点击记录
     */
    boolean hasClick(String userId, String thingId);
}
