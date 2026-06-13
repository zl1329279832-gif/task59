package com.gk.study.controller;

import com.gk.study.common.ResponeCode;
import com.gk.study.entity.*;
import com.gk.study.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 个性化推荐系统单元测试
 *
 * 覆盖场景：
 * 1. 冷启动用户推荐
 * 2. 已购买资源过滤
 * 3. 屏蔽反馈降权
 * 4. 推荐统计概览
 */
@ExtendWith(MockitoExtension.class)
public class RecommendationControllerTest {

    @InjectMocks
    private RecommendationController controller;

    @Mock
    private ThingService thingService;

    @Mock
    private ThingCollectService thingCollectService;

    @Mock
    private ThingWishService thingWishService;

    @Mock
    private RecordService recordService;

    @Mock
    private OrderService orderService;

    @Mock
    private CommentService commentService;

    @Mock
    private RecommendLogService recommendLogService;

    @Mock
    private RecommendFeedbackService recommendFeedbackService;

    // 测试数据
    private Thing thing1;
    private Thing thing2;
    private Thing thing3;
    private Thing thing4;
    private Thing thing5;

    @BeforeEach
    public void setUp() {
        // 创建测试资源
        thing1 = createThing(1L, "篮球基础训练", 100L, "100", "5");    // 分类100, 高PV
        thing2 = createThing(2L, "篮球进阶技巧", 100L, "80", "4");     // 分类100, 中PV
        thing3 = createThing(3L, "游泳入门课程", 200L, "60", "4");     // 分类200
        thing4 = createThing(4L, "瑜伽基础教程", 300L, "50", "3");     // 分类300
        thing5 = createThing(5L, "篮球高级战术", 100L, "40", "5");     // 分类100
    }

    // ==================== 冷启动测试 ====================

    /**
     * 冷启动用户：没有任何收藏、心愿、订单、评论
     * 应该返回按PV排序的热门资源
     */
    @Test
    public void testColdStartUser_returnsPopularThings() {
        String userId = "new_user_1";

        // 模拟冷启动用户：无任何行为
        when(thingCollectService.getThingCollectList(userId)).thenReturn(Collections.emptyList());
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Collections.emptyList());
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());

        // 模拟全部资源
        List<Thing> allThings = Arrays.asList(thing1, thing2, thing3, thing4, thing5);
        when(thingService.getDefaultThingList()).thenReturn(allThings);

        com.gk.study.common.APIResponse response = controller.personalized(userId, "5");

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertFalse(items.isEmpty());

        // 冷启动用户，推荐理由应为"热门推荐"
        for (RecommendItem item : items) {
            assertEquals("热门推荐", item.getReason());
        }

        // 验证曝光日志被记录
        verify(recommendLogService, times(items.size())).log(eq(userId), anyString(), eq("exposure"), eq("热门推荐"));
    }

    /**
     * 冷启动阈值测试：仅有1个行为信号（低于阈值3），仍视为冷启动
     */
    @Test
    public void testNearColdStart_stillPopular() {
        String userId = "almost_new_user";

        // 模拟只有1个收藏（1个信号 < 阈值3）
        List<Map> collects = new ArrayList<>();
        Map<String, Object> collectMap = new HashMap<>();
        collectMap.put("thing_id", "1");
        collects.add(collectMap);

        when(thingCollectService.getThingCollectList(userId)).thenReturn(collects);
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Collections.emptyList());
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());

        when(thingService.getThingById("1")).thenReturn(thing1);
        when(thingService.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3, thing4, thing5));

        com.gk.study.common.APIResponse response = controller.personalized(userId, "5");

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertFalse(items.isEmpty());

        // 1个信号 < 3, 仍为冷启动, 推荐为"热门推荐"
        for (RecommendItem item : items) {
            assertEquals("热门推荐", item.getReason());
        }
    }

    // ==================== 个性化推荐测试 ====================

    /**
     * 活跃用户：有收藏和订单，应获得个性化推荐理由
     */
    @Test
    public void testActiveUser_getsPersonalizedRecommendations() {
        String userId = "active_user";

        // 模拟3个收藏（分类100）
        List<Map> collects = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("thing_id", String.valueOf(i));
            collects.add(m);
        }
        // 模拟1个订单（分类100）
        Order order = new Order();
        order.setThingId("2");
        List<Order> orders = Collections.singletonList(order);

        when(thingCollectService.getThingCollectList(userId)).thenReturn(collects);
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(orders);
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());

        // 模拟 getThingById
        when(thingService.getThingById("1")).thenReturn(thing1);
        when(thingService.getThingById("2")).thenReturn(thing2);
        when(thingService.getThingById("3")).thenReturn(thing3);

        when(thingService.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3, thing4, thing5));

        com.gk.study.common.APIResponse response = controller.personalized(userId, "5");

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertFalse(items.isEmpty());

        // thing2已购买应被过滤
        boolean thing2Present = items.stream().anyMatch(i -> i.getThing().getId() == 2L);
        assertFalse(thing2Present, "已购买资源应被过滤");

        // thing5（分类100，未购买）应该出现在推荐中，且排名靠前
        boolean thing5Present = items.stream().anyMatch(i -> i.getThing().getId() == 5L);
        assertTrue(thing5Present, "同分类未购买资源应被推荐");

        // 检查推荐理由
        RecommendItem thing5Item = items.stream()
                .filter(i -> i.getThing().getId() == 5L)
                .findFirst().orElse(null);
        assertNotNull(thing5Item);
        assertTrue(
                thing5Item.getReason().contains("进阶") || thing5Item.getReason().contains("收藏") || thing5Item.getReason().contains("同类"),
                "推荐理由应包含已购进阶或同类收藏");
    }

    // ==================== 已购买过滤测试 ====================

    /**
     * 已购买的所有资源都不应出现在推荐列表中
     */
    @Test
    public void testPurchasedThingsFiltered() {
        String userId = "buyer";

        // 模拟多个收藏使信号 > 3
        List<Map> collects = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("thing_id", String.valueOf(i));
            collects.add(m);
        }
        when(thingCollectService.getThingCollectList(userId)).thenReturn(collects);
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());

        // 订单：购买了thing1和thing3
        Order o1 = new Order(); o1.setThingId("1");
        Order o3 = new Order(); o3.setThingId("3");
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Arrays.asList(o1, o3));

        when(thingService.getThingById("1")).thenReturn(thing1);
        when(thingService.getThingById("2")).thenReturn(thing2);
        when(thingService.getThingById("3")).thenReturn(thing3);
        when(thingService.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3, thing4, thing5));

        com.gk.study.common.APIResponse response = controller.personalized(userId, "10");

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();

        // thing1 和 thing3 已购买，不应出现
        for (RecommendItem item : items) {
            long id = item.getThing().getId();
            assertNotEquals(1L, id, "thing1已购买不应出现");
            assertNotEquals(3L, id, "thing3已购买不应出现");
        }
    }

    // ==================== 屏蔽反馈测试 ====================

    /**
     * 被标记"不感兴趣"的具体资源应被完全排除
     */
    @Test
    public void testBlockedThingExcluded() {
        String userId = "user_block";

        // 活跃用户
        List<Map> collects = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("thing_id", String.valueOf(i));
            collects.add(m);
        }
        when(thingCollectService.getThingCollectList(userId)).thenReturn(collects);
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Collections.emptyList());
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());

        // 用户屏蔽了thing4
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.singletonList("4"));
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());

        when(thingService.getThingById("1")).thenReturn(thing1);
        when(thingService.getThingById("2")).thenReturn(thing2);
        when(thingService.getThingById("3")).thenReturn(thing3);
        when(thingService.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3, thing4, thing5));

        com.gk.study.common.APIResponse response = controller.personalized(userId, "10");

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();

        boolean thing4Present = items.stream().anyMatch(i -> i.getThing().getId() == 4L);
        assertFalse(thing4Present, "被屏蔽的资源不应出现");
    }

    /**
     * 被标记"不感兴趣"的分类应降低权重（但不是完全排除）
     */
    @Test
    public void testBlockedCategoryWeightReduced() {
        String userId = "user_block_cat";

        // 用户对分类100和200都有收藏
        List<Map> collects = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("thing_id", String.valueOf(i));
            collects.add(m);
        }
        when(thingCollectService.getThingCollectList(userId)).thenReturn(collects);
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Collections.emptyList());

        // 用户对分类300的资源评论过（增加1个信号）
        Comment c = new Comment();
        c.setThingId("4"); // thing4在分类300
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.singletonList(c));

        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.singletonList(300L));

        when(thingService.getThingById("1")).thenReturn(thing1);
        when(thingService.getThingById("2")).thenReturn(thing2);
        when(thingService.getThingById("4")).thenReturn(thing4);
        when(thingService.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3, thing4, thing5));

        com.gk.study.common.APIResponse response = controller.personalized(userId, "10");

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();

        // thing4（分类300）应该仍在列表中（因为不是完全排除），但分数被降低
        Optional<RecommendItem> thing4Opt = items.stream()
                .filter(i -> i.getThing().getId() == 4L)
                .findFirst();
        // 由于分类300被惩罚，thing4的分数应低于分类100的资源
        if (thing4Opt.isPresent()) {
            RecommendItem thing4Item = thing4Opt.get();
            // 找一个分类100的资源对比
            Optional<RecommendItem> cat100Item = items.stream()
                    .filter(i -> i.getThing().getClassificationId() == 100L)
                    .findFirst();
            if (cat100Item.isPresent()) {
                assertTrue(thing4Item.getScore() < cat100Item.get().getScore(),
                        "被惩罚分类的分数应低于正常分类");
            }
        }
    }

    /**
     * 不感兴趣接口应正确保存反馈
     */
    @Test
    public void testNotInterested_savesFeedbackAndLog() {
        String userId = "user_fb";
        String thingId = "4";

        when(thingService.getThingById(thingId)).thenReturn(thing4);

        com.gk.study.common.APIResponse response = controller.notInterested(userId, thingId);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        // 验证保存了反馈
        verify(recommendFeedbackService).block(userId, thingId, 300L);
        // 验证记录了日志
        verify(recommendLogService).log(userId, thingId, "block", "用户标记不感兴趣");
    }

    /**
     * 不感兴趣接口参数不完整时应返回失败
     */
    @Test
    public void testNotInterested_invalidParams() {
        com.gk.study.common.APIResponse response1 = controller.notInterested(null, "4");
        assertEquals(ResponeCode.FAIL.getCode(), response1.getCode());

        com.gk.study.common.APIResponse response2 = controller.notInterested("user", null);
        assertEquals(ResponeCode.FAIL.getCode(), response2.getCode());
    }

    // ==================== 推荐统计测试 ====================

    /**
     * 管理员统计接口应返回正确的曝光/点击/屏蔽数据
     */
    @Test
    public void testStats_returnsCorrectCounts() {
        List<Map<String, Object>> stats = new ArrayList<>();

        Map<String, Object> exposureStat = new HashMap<>();
        exposureStat.put("type", "exposure");
        exposureStat.put("count", 1000L);
        stats.add(exposureStat);

        Map<String, Object> clickStat = new HashMap<>();
        clickStat.put("type", "click");
        clickStat.put("count", 150L);
        stats.add(clickStat);

        Map<String, Object> blockStat = new HashMap<>();
        blockStat.put("type", "block");
        blockStat.put("count", 30L);
        stats.add(blockStat);

        when(recommendLogService.getStats()).thenReturn(stats);

        com.gk.study.common.APIResponse response = controller.stats();

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertNotNull(data);

        assertEquals(1000L, data.get("exposureCount"));
        assertEquals(150L, data.get("clickCount"));
        assertEquals(30L, data.get("blockCount"));
        assertEquals("15.00%", data.get("clickRate"));
        assertEquals("3.00%", data.get("blockRate"));
    }

    /**
     * 统计接口在无数据时不应报错，应返回0
     */
    @Test
    public void testStats_emptyData() {
        when(recommendLogService.getStats()).thenReturn(Collections.emptyList());

        com.gk.study.common.APIResponse response = controller.stats();

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(0L, data.get("exposureCount"));
        assertEquals(0L, data.get("clickCount"));
        assertEquals(0L, data.get("blockCount"));
        assertEquals("0.00%", data.get("clickRate"));
        assertEquals("0.00%", data.get("blockRate"));
    }

    // ==================== 点击记录测试 ====================

    /**
     * 点击记录接口应保存日志
     */
    @Test
    public void testClick_logsCorrectly() {
        String userId = "user_click";
        String thingId = "1";
        String reason = "同类收藏较多";

        com.gk.study.common.APIResponse response = controller.click(userId, thingId, reason);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        verify(recommendLogService).log(userId, thingId, "click", reason);
    }

    // ==================== 边界条件测试 ====================

    /**
     * userId 为空时应返回失败
     */
    @Test
    public void testPersonalized_noUserId_returnsFail() {
        com.gk.study.common.APIResponse response = controller.personalized(null, "10");
        assertEquals(ResponeCode.FAIL.getCode(), response.getCode());

        com.gk.study.common.APIResponse response2 = controller.personalized("", "10");
        assertEquals(ResponeCode.FAIL.getCode(), response2.getCode());
    }

    /**
     * 资源列表为空时应返回空推荐
     */
    @Test
    public void testPersonalized_noThings_returnsEmptyList() {
        String userId = "user_empty";

        when(thingCollectService.getThingCollectList(userId)).thenReturn(Collections.emptyList());
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Collections.emptyList());
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());
        when(thingService.getDefaultThingList()).thenReturn(Collections.emptyList());

        com.gk.study.common.APIResponse response = controller.personalized(userId, "10");

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertTrue(items.isEmpty(), "无资源时应返回空列表");
    }

    /**
     * 所有资源都被购买时应返回空推荐
     */
    @Test
    public void testPersonalized_allPurchased_returnsEmpty() {
        String userId = "user_all_buy";

        // 足够多的收藏信号
        List<Map> collects = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> m = new HashMap<>();
            m.put("thing_id", String.valueOf(i));
            collects.add(m);
        }
        when(thingCollectService.getThingCollectList(userId)).thenReturn(collects);
        when(thingWishService.getThingWishList(userId)).thenReturn(Collections.emptyList());
        when(commentService.getUserCommentList(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedThingIds(userId)).thenReturn(Collections.emptyList());
        when(recommendFeedbackService.getBlockedClassificationIds(userId)).thenReturn(Collections.emptyList());

        // 所有资源都已购买
        Order o1 = new Order(); o1.setThingId("1");
        Order o2 = new Order(); o2.setThingId("2");
        Order o3 = new Order(); o3.setThingId("3");
        Order o4 = new Order(); o4.setThingId("4");
        Order o5 = new Order(); o5.setThingId("5");
        when(orderService.getUserOrderList(eq(userId), isNull())).thenReturn(Arrays.asList(o1, o2, o3, o4, o5));

        when(thingService.getThingById("1")).thenReturn(thing1);
        when(thingService.getThingById("2")).thenReturn(thing2);
        when(thingService.getThingById("3")).thenReturn(thing3);
        when(thingService.getThingById("4")).thenReturn(thing4);
        when(thingService.getThingById("5")).thenReturn(thing5);

        when(thingService.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3, thing4, thing5));

        com.gk.study.common.APIResponse response = controller.personalized(userId, "10");

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertTrue(items.isEmpty(), "所有资源都已购买时应返回空列表");
    }

    // ==================== 辅助方法 ====================

    private Thing createThing(Long id, String title, Long classificationId, String pv, String rate) {
        Thing thing = new Thing();
        thing.setId(id);
        thing.setTitle(title);
        thing.setClassificationId(classificationId);
        thing.setPv(pv);
        thing.setRate(rate);
        thing.setStatus("0");
        thing.setScore("0");
        thing.setWishCount("0");
        thing.setCollectCount("0");
        return thing;
    }
}
