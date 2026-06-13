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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/recommendation")
public class RecommendationController {

    private final static Logger logger = LoggerFactory.getLogger(RecommendationController.class);

    private static final int DEFAULT_RECOMMEND_SIZE = 10;
    private static final int COLD_START_THRESHOLD = 3;
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
    RecommendFeedbackService recommendFeedbackService;

    @Autowired
    RecommendLogService recommendLogService;

    @Access(level = AccessLevel.LOGIN)
    @RequestMapping(value = "/personalized", method = RequestMethod.GET)
    public APIResponse personalized(String userId, String size) {
        if (StringUtils.isEmpty(userId)) {
            return new APIResponse(ResponeCode.FAIL, "用户ID不能为空");
        }

        int recommendSize = DEFAULT_RECOMMEND_SIZE;
        if (!StringUtils.isEmpty(size)) {
            try {
                recommendSize = Integer.parseInt(size);
            } catch (NumberFormatException e) {
                recommendSize = DEFAULT_RECOMMEND_SIZE;
            }
        }

        List<Thing> allThings = thingService.getThingList(null, null, null, null);
        if (allThings == null || allThings.isEmpty()) {
            return new APIResponse(ResponeCode.SUCCESS, "查询成功", new ArrayList<>());
        }

        // 获取用户行为数据
        List<Map> collectList = thingCollectService.getThingCollectList(userId);
        List<Order> orderList = orderService.getUserOrderList(userId, null);
        List<Map> wishList = thingWishService.getThingWishList(userId);

        int interactionCount = (collectList != null ? collectList.size() : 0)
                + (orderList != null ? orderList.size() : 0)
                + (wishList != null ? wishList.size() : 0);

        // 冷启动：交互不足则返回热门资源
        if (interactionCount < COLD_START_THRESHOLD) {
            List<Thing> popularThings = thingService.getDefaultThingList();
            if (popularThings == null) {
                popularThings = new ArrayList<>();
            }
            List<RecommendItem> items = popularThings.stream()
                    .limit(recommendSize)
                    .map(thing -> new RecommendItem(thing, "热门推荐", parseDoubleSafe(thing.pv)))
                    .collect(Collectors.toList());
            for (RecommendItem item : items) {
                recommendLogService.log(userId, String.valueOf(item.getThing().getId()), "expose", item.getReason());
            }
            return new APIResponse(ResponeCode.SUCCESS, "查询成功", items);
        }

        // 获取已购买的资源ID
        Set<String> purchasedThingIds = getPurchasedThingIds(orderList);

        // 获取屏蔽信息
        List<String> blockedThingIds = recommendFeedbackService.getBlockedThingIds(userId);
        List<Long> blockedClassificationIds = recommendFeedbackService.getBlockedClassificationIds(userId);

        // 构建分类偏好
        Map<Long, Integer> categoryPreference = buildCategoryPreference(collectList, allThings);

        // 获取已购资源的分类集合
        Set<Long> purchasedCategoryIds = new HashSet<>();
        for (String pid : purchasedThingIds) {
            Thing t = safeGetThing(allThings, pid);
            if (t != null && t.classificationId != null) {
                purchasedCategoryIds.add(t.classificationId);
            }
        }

        // 对每个资源打分
        List<RecommendItem> candidates = new ArrayList<>();
        for (Thing thing : allThings) {
            String thingIdStr = String.valueOf(thing.getId());

            // 过滤已购买
            if (purchasedThingIds.contains(thingIdStr)) {
                continue;
            }
            // 过滤已屏蔽
            if (blockedThingIds.contains(thingIdStr)) {
                continue;
            }

            double score = 0;
            String reason = "热门推荐";

            // 同类收藏较多
            if (thing.classificationId != null && categoryPreference.containsKey(thing.classificationId)) {
                int collectCount = categoryPreference.get(thing.classificationId);
                score += collectCount * 10;
                reason = "同类收藏较多";
            }

            // 已购资源的进阶内容
            if (thing.classificationId != null && purchasedCategoryIds.contains(thing.classificationId)
                    && hasPurchasedInCategory(orderList, thing.classificationId, allThings)) {
                score += 8;
                if (score < 10) {
                    reason = "已购资源的进阶内容";
                }
            }

            // 浏览过相近分类（基于心愿单推断浏览兴趣）
            if (wishList != null) {
                for (Map wish : wishList) {
                    Object wishThingId = wish.get("thingId");
                    if (wishThingId == null) wishThingId = wish.get("thing_id");
                    if (wishThingId != null) {
                        Thing wishThing = safeGetThing(allThings, String.valueOf(wishThingId));
                        if (wishThing != null && wishThing.classificationId != null
                                && wishThing.classificationId.equals(thing.classificationId)) {
                            score += 5;
                            if (score < 10) {
                                reason = "浏览过相近分类";
                            }
                            break;
                        }
                    }
                }
            }

            // 基础热度分
            score += parseDoubleSafe(thing.pv) * 0.01;

            // 屏蔽分类惩罚
            if (thing.classificationId != null && blockedClassificationIds.contains(thing.classificationId)) {
                score *= BLOCKED_CATEGORY_PENALTY;
            }

            if (score > 0) {
                candidates.add(new RecommendItem(thing, reason, score));
            }
        }

        // 按分数排序取前N个
        candidates.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        List<RecommendItem> result = candidates.stream()
                .limit(recommendSize)
                .collect(Collectors.toList());

        // 记录曝光日志
        for (RecommendItem item : result) {
            recommendLogService.log(userId, String.valueOf(item.getThing().getId()), "expose", item.getReason());
        }

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", result);
    }

    @Access(level = AccessLevel.LOGIN)
    @RequestMapping(value = "/notInterested", method = RequestMethod.POST)
    public APIResponse notInterested(String userId, String thingId) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(thingId)) {
            return new APIResponse(ResponeCode.FAIL, "参数不完整");
        }

        Thing thing = thingService.getThingById(thingId);
        Long classificationId = null;
        if (thing != null) {
            classificationId = thing.classificationId;
        }

        recommendFeedbackService.block(userId, thingId, classificationId);
        recommendLogService.log(userId, thingId, "block", "不感兴趣");

        return new APIResponse(ResponeCode.SUCCESS, "操作成功");
    }

    @Access(level = AccessLevel.LOGIN)
    @RequestMapping(value = "/click", method = RequestMethod.POST)
    public APIResponse click(String userId, String thingId, String source) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(thingId)) {
            return new APIResponse(ResponeCode.FAIL, "参数不完整");
        }

        recommendLogService.log(userId, thingId, "click", source);

        return new APIResponse(ResponeCode.SUCCESS, "操作成功");
    }

    @Access(level = AccessLevel.ADMIN)
    @RequestMapping(value = "/stats", method = RequestMethod.GET)
    public APIResponse stats() {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> statsDetail = recommendLogService.getStats();
        result.put("detail", statsDetail);

        int exposeCount = recommendLogService.countByType("expose");
        int clickCount = recommendLogService.countByType("click");
        int blockCount = recommendLogService.countByType("block");

        result.put("exposeCount", exposeCount);
        result.put("clickCount", clickCount);
        result.put("blockCount", blockCount);

        return new APIResponse(ResponeCode.SUCCESS, "查询成功", result);
    }

    private Map<Long, Integer> buildCategoryPreference(List<Map> collectList, List<Thing> allThings) {
        Map<Long, Integer> preference = new HashMap<>();
        if (collectList == null) return preference;
        for (Map collect : collectList) {
            Object thingIdObj = collect.get("thingId");
            if (thingIdObj == null) thingIdObj = collect.get("thing_id");
            if (thingIdObj != null) {
                Thing thing = safeGetThing(allThings, String.valueOf(thingIdObj));
                if (thing != null && thing.classificationId != null) {
                    preference.merge(thing.classificationId, 1, Integer::sum);
                }
            }
        }
        return preference;
    }

    private Set<String> getPurchasedThingIds(List<Order> orderList) {
        Set<String> ids = new HashSet<>();
        if (orderList == null) return ids;
        for (Order order : orderList) {
            if (order.getThingId() != null) {
                ids.add(order.getThingId());
            }
        }
        return ids;
    }

    private boolean hasPurchasedInCategory(List<Order> orderList, Long classificationId, List<Thing> allThings) {
        if (orderList == null) return false;
        for (Order order : orderList) {
            Thing thing = safeGetThing(allThings, order.getThingId());
            if (thing != null && classificationId.equals(thing.classificationId)) {
                return true;
            }
        }
        return false;
    }

    private Thing safeGetThing(List<Thing> things, String thingId) {
        if (things == null || thingId == null) return null;
        for (Thing thing : things) {
            if (thing.getId() != null && String.valueOf(thing.getId()).equals(thingId)) {
                return thing;
            }
        }
        return null;
    }

    private double parseDoubleSafe(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
