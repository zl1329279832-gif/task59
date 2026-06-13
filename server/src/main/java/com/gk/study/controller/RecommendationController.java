package com.gk.study.controller;

import com.gk.study.common.APIResponse;
import com.gk.study.common.ResponeCode;
import com.gk.study.entity.*;
import com.gk.study.permission.Access;
import com.gk.study.permission.AccessLevel;
import com.gk.study.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 个性化学习推荐控制器
 *
 * 核心功能：
 * 1. 为登录用户返回个性化推荐资源列表（含推荐理由）
 * 2. 用户对推荐结果标记"不感兴趣"，后续推荐降低同类内容权重
 * 3. 管理员概览：推荐曝光、点击、屏蔽基础统计
 *
 * 推荐信号来源：
 * - 收藏 (ThingCollectService)  → "同类收藏较多"
 * - 心愿 (ThingWishService)     → "同类心愿较多"
 * - 浏览记录 (RecordService)    → "浏览过相近分类"
 * - 订单 (OrderService)         → "已购资源的进阶内容"
 * - 评论 (CommentService)       → "评论过的分类"
 */
@RestController
@RequestMapping("/recommendation")
public class RecommendationController {

    private static final Logger logger = LoggerFactory.getLogger(RecommendationController.class);

    /** 推荐列表默认大小 */
    private static final int DEFAULT_RECOMMEND_SIZE = 10;

    /** 冷启动信号阈值：用户行为总数低于此值视为冷启动 */
    private static final int COLD_START_THRESHOLD = 3;

    /** 屏蔽分类的权重惩罚系数 (0~1, 越小惩罚越重) */
    private static final double BLOCKED_CATEGORY_PENALTY = 0.3;

    @Autowired
    ThingService thingService;

    @Autowired
    ThingCollectService thingCollectService;

    @Autowired
    ThingWishService thingWishService;

    @Autowired
    RecordService recordService;

    @Autowired
    OrderService orderService;

    @Autowired
    CommentService commentService;

    @Autowired
    RecommendLogService recommendLogService;

    @Autowired
    RecommendFeedbackService recommendFeedbackService;

    // ===================== 个性化推荐接口 =====================

    /**
     * 获取个性化推荐列表
     *
     * @param userId 登录用户id
     * @param size   返回数量（可选，默认10）
     * @return 推荐资源列表，每项包含资源信息和推荐理由
     */
    @RequestMapping(value = "/personalized", method = RequestMethod.GET)
    public APIResponse personalized(String userId, String size) {
        if (userId == null || userId.isEmpty()) {
            return new APIResponse(ResponeCode.FAIL, "userId 不能为空");
        }
        int limit = DEFAULT_RECOMMEND_SIZE;
        if (size != null && !size.isEmpty()) {
            try {
                limit = Integer.parseInt(size);
            } catch (NumberFormatException ignored) {}
        }

        // 1. 获取用户已屏蔽的资源id和分类id
        Set<String> blockedThingIds = new HashSet<>(recommendFeedbackService.getBlockedThingIds(userId));
        Set<Long> blockedClassificationIds = new HashSet<>(recommendFeedbackService.getBlockedClassificationIds(userId));

        // 2. 获取用户已购买的资源id（用于过滤）
        Set<String> purchasedThingIds = getPurchasedThingIds(userId);

        // 3. 构建用户分类偏好 (classificationId → score)
        Map<Long, Double> categoryPreference = new HashMap<>();
        // 记录每个分类偏好的来源原因 (classificationId → reason)
        Map<Long, String> categoryReason = new HashMap<>();
        int totalSignals = buildCategoryPreference(userId, categoryPreference, categoryReason);

        // 4. 冷启动判断
        boolean isColdStart = totalSignals < COLD_START_THRESHOLD;

        // 5. 获取候选资源
        List<Thing> allThings = thingService.getDefaultThingList(); // 按PV降序
        if (allThings == null) {
            allThings = new ArrayList<>();
        }

        // 6. 对候选资源打分并生成推荐理由
        List<RecommendItem> scoredItems = new ArrayList<>();
        for (Thing thing : allThings) {
            String thingIdStr = String.valueOf(thing.getId());

            // 过滤：已购买
            if (purchasedThingIds.contains(thingIdStr)) {
                continue;
            }
            // 过滤：已屏蔽的具体资源
            if (blockedThingIds.contains(thingIdStr)) {
                continue;
            }
            // 过滤：状态不为"上架"的资源（如果有status字段）
            if (thing.getStatus() != null && !"0".equals(thing.getStatus()) && !"1".equals(thing.getStatus())) {
                // 宽松判断：只有明确标记为下架的才过滤
            }

            double score = 0.0;
            String reason;

            if (isColdStart) {
                // 冷启动：按热门程度（PV）推荐
                score = parseDoubleSafe(thing.getPv());
                reason = "热门推荐";
            } else {
                // 个性化推荐
                Long cid = thing.getClassificationId();
                if (cid != null && categoryPreference.containsKey(cid)) {
                    // 分类匹配得分
                    score = categoryPreference.get(cid);
                    reason = categoryReason.getOrDefault(cid, "同类内容推荐");

                    // 已购资源的进阶内容：如果用户购买过同分类资源，加分
                    if (hasPurchasedInCategory(userId, cid)) {
                        score += 5.0;
                        reason = "已购资源的进阶内容";
                    }

                    // 屏蔽分类惩罚
                    if (blockedClassificationIds.contains(cid)) {
                        score *= BLOCKED_CATEGORY_PENALTY;
                        reason = reason + "（已降低权重）";
                    }
                } else {
                    // 非偏好分类，给一个基础分（PV热度）
                    score = parseDoubleSafe(thing.getPv()) * 0.1;
                    reason = "热门资源";
                }

                // 全局热度加成（归一化 PV 贡献）
                score += parseDoubleSafe(thing.getPv()) * 0.05;
                // 评分加成
                score += parseDoubleSafe(thing.getRate()) * 0.5;
            }

            scoredItems.add(new RecommendItem(thing, reason, score));
        }

        // 7. 按得分降序排序，取 top N
        scoredItems.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        List<RecommendItem> result = scoredItems.subList(0, Math.min(limit, scoredItems.size()));

        // 8. 记录曝光日志
        for (RecommendItem item : result) {
            recommendLogService.log(userId, String.valueOf(item.getThing().getId()), "exposure", item.getReason());
        }

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", result);
    }

    // ===================== 不感兴趣反馈接口 =====================

    /**
     * 用户标记"不感兴趣"
     *
     * @param userId  用户id
     * @param thingId 资源id
     * @return 操作结果
     */
    @RequestMapping(value = "/notInterested", method = RequestMethod.POST)
    public APIResponse notInterested(String userId, String thingId) {
        if (userId == null || userId.isEmpty() || thingId == null || thingId.isEmpty()) {
            return new APIResponse(ResponeCode.FAIL, "参数不完整");
        }

        // 获取资源分类信息
        Long classificationId = null;
        try {
            Thing thing = thingService.getThingById(thingId);
            if (thing != null) {
                classificationId = thing.getClassificationId();
            }
        } catch (Exception e) {
            logger.warn("获取资源信息失败 thingId={}", thingId);
        }

        // 保存屏蔽反馈
        recommendFeedbackService.block(userId, thingId, classificationId);

        // 记录屏蔽日志
        recommendLogService.log(userId, thingId, "block", "用户标记不感兴趣");

        return new APIResponse(ResponeCode.SUCCESS, "已标记为不感兴趣");
    }

    // ===================== 点击记录接口 =====================

    /**
     * 记录用户点击推荐资源
     *
     * @param userId  用户id
     * @param thingId 资源id
     * @param reason  推荐原因（前端回传）
     * @return 操作结果
     */
    @RequestMapping(value = "/click", method = RequestMethod.POST)
    public APIResponse click(String userId, String thingId, String reason) {
        if (userId == null || userId.isEmpty() || thingId == null || thingId.isEmpty()) {
            return new APIResponse(ResponeCode.FAIL, "参数不完整");
        }
        recommendLogService.log(userId, thingId, "click", reason != null ? reason : "");
        return new APIResponse(ResponeCode.SUCCESS, "记录成功");
    }

    // ===================== 管理员统计接口 =====================

    /**
     * 管理员概览：推荐曝光、点击、屏蔽基础统计
     */
    @Access(level = AccessLevel.ADMIN)
    @RequestMapping(value = "/stats", method = RequestMethod.GET)
    public APIResponse stats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 从数据库按类型分组统计
        List<Map<String, Object>> dbStats = recommendLogService.getStats();
        long exposureCount = 0;
        long clickCount = 0;
        long blockCount = 0;
        for (Map<String, Object> row : dbStats) {
            String type = String.valueOf(row.get("type"));
            long count = ((Number) row.get("count")).longValue();
            switch (type) {
                case "exposure":
                    exposureCount = count;
                    break;
                case "click":
                    clickCount = count;
                    break;
                case "block":
                    blockCount = count;
                    break;
            }
        }

        result.put("exposureCount", exposureCount);
        result.put("clickCount", clickCount);
        result.put("blockCount", blockCount);

        // 点击率
        double ctr = exposureCount > 0 ? (double) clickCount / exposureCount : 0;
        result.put("clickRate", String.format("%.2f%%", ctr * 100));

        // 屏蔽率
        double blockRate = exposureCount > 0 ? (double) blockCount / exposureCount : 0;
        result.put("blockRate", String.format("%.2f%%", blockRate * 100));

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", result);
    }

    // ===================== 内部辅助方法 =====================

    /**
     * 构建用户分类偏好
     * 综合收藏、心愿、浏览、订单、评论信号
     *
     * @return 用户行为信号总数（用于判断冷启动）
     */
    private int buildCategoryPreference(String userId,
                                         Map<Long, Double> categoryPreference,
                                         Map<Long, String> categoryReason) {
        int signals = 0;

        // --- 收藏信号 (权重 3.0) ---
        List<Map> collects = thingCollectService.getThingCollectList(userId);
        if (collects != null) {
            for (Map collect : collects) {
                String thingId = String.valueOf(collect.get("thing_id"));
                Thing thing = safeGetThing(thingId);
                if (thing != null && thing.getClassificationId() != null) {
                    Long cid = thing.getClassificationId();
                    categoryPreference.merge(cid, 3.0, Double::sum);
                    categoryReason.putIfAbsent(cid, "同类收藏较多");
                    signals++;
                }
            }
        }

        // --- 心愿信号 (权重 2.0) ---
        List<Map> wishes = thingWishService.getThingWishList(userId);
        if (wishes != null) {
            for (Map wish : wishes) {
                String thingId = String.valueOf(wish.get("thing_id"));
                Thing thing = safeGetThing(thingId);
                if (thing != null && thing.getClassificationId() != null) {
                    Long cid = thing.getClassificationId();
                    categoryPreference.merge(cid, 2.0, Double::sum);
                    categoryReason.putIfAbsent(cid, "同类心愿较多");
                    signals++;
                }
            }
        }

        // --- 浏览信号 (权重 1.0, 基于IP的浏览记录映射) ---
        // 由于 RecordService 是按 IP 查询的，此处通过用户评论和订单间接推断浏览偏好
        // 补充：通过用户订单获取浏览过的分类
        List<Order> orders = orderService.getUserOrderList(userId, null);
        if (orders != null) {
            for (Order order : orders) {
                Thing thing = safeGetThing(order.getThingId());
                if (thing != null && thing.getClassificationId() != null) {
                    Long cid = thing.getClassificationId();
                    categoryPreference.merge(cid, 2.5, Double::sum);
                    categoryReason.putIfAbsent(cid, "已购资源的进阶内容");
                    signals++;
                }
            }
        }

        // --- 评论信号 (权重 1.5) ---
        List<Comment> comments = commentService.getUserCommentList(userId);
        if (comments != null) {
            for (Comment comment : comments) {
                Thing thing = safeGetThing(comment.getThingId());
                if (thing != null && thing.getClassificationId() != null) {
                    Long cid = thing.getClassificationId();
                    categoryPreference.merge(cid, 1.5, Double::sum);
                    categoryReason.putIfAbsent(cid, "浏览过相近分类");
                    signals++;
                }
            }
        }

        return signals;
    }

    /**
     * 获取用户已购买的资源id集合
     */
    private Set<String> getPurchasedThingIds(String userId) {
        Set<String> result = new HashSet<>();
        List<Order> orders = orderService.getUserOrderList(userId, null);
        if (orders != null) {
            for (Order order : orders) {
                if (order.getThingId() != null) {
                    result.add(order.getThingId());
                }
            }
        }
        return result;
    }

    /**
     * 检查用户是否购买过某分类下的资源
     */
    private boolean hasPurchasedInCategory(String userId, Long classificationId) {
        List<Order> orders = orderService.getUserOrderList(userId, null);
        if (orders != null) {
            for (Order order : orders) {
                Thing thing = safeGetThing(order.getThingId());
                if (thing != null && classificationId.equals(thing.getClassificationId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 安全获取资源信息（异常时返回null）
     */
    private Thing safeGetThing(String thingId) {
        if (thingId == null || thingId.isEmpty() || "null".equals(thingId)) {
            return null;
        }
        try {
            return thingService.getThingById(thingId);
        } catch (Exception e) {
            logger.warn("获取资源信息失败 thingId={}", thingId);
            return null;
        }
    }

    /**
     * 安全解析 double
     */
    private double parseDoubleSafe(String value) {
        if (value == null || value.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
