package com.gk.study.controller;

import com.gk.study.common.APIResponse;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RecommendationControllerTest {

    @Mock
    ThingService thingService;
    @Mock
    ThingCollectService thingCollectService;
    @Mock
    ThingWishService thingWishService;
    @Mock
    RecordService recordService;
    @Mock
    OrderService orderService;
    @Mock
    CommentService commentService;
    @Mock
    RecommendFeedbackService recommendFeedbackService;
    @Mock
    RecommendLogService recommendLogService;

    @InjectMocks
    RecommendationController controller;

    private List<Thing> allThings;

    @BeforeEach
    void setUp() {
        allThings = new ArrayList<>();
        allThings.add(createThing(1L, "Java入门", 100L, "100", "5"));
        allThings.add(createThing(2L, "Java进阶", 100L, "80", "4"));
        allThings.add(createThing(3L, "Python基础", 200L, "60", "3"));
        allThings.add(createThing(4L, "Python高级", 200L, "40", "4"));
        allThings.add(createThing(5L, "前端开发", 300L, "120", "5"));
    }

    private Thing createThing(Long id, String title, Long classificationId, String pv, String rate) {
        Thing thing = new Thing();
        thing.id = id;
        thing.title = title;
        thing.classificationId = classificationId;
        thing.pv = pv;
        thing.rate = rate;
        thing.collectCount = "0";
        thing.wishCount = "0";
        thing.recommendCount = "0";
        thing.score = "0";
        thing.price = "0";
        thing.status = "0";
        return thing;
    }

    // ========== 冷启动测试 ==========

    @Test
    void testColdStartUser_returnsPopularThings() {
        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user1")).thenReturn(new ArrayList<>());
        when(orderService.getUserOrderList("user1", null)).thenReturn(new ArrayList<>());
        when(thingWishService.getThingWishList("user1")).thenReturn(new ArrayList<>());
        when(thingService.getDefaultThingList()).thenReturn(allThings);

        APIResponse response = controller.personalized("user1", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);
        assertFalse(items.isEmpty());
        assertEquals("热门推荐", items.get(0).getReason());
    }

    @Test
    void testNearColdStart_stillPopular() {
        // 只有2个交互，低于阈值3
        List<Map> collectList = new ArrayList<>();
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("2"));

        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user2")).thenReturn(collectList);
        when(orderService.getUserOrderList("user2", null)).thenReturn(new ArrayList<>());
        when(thingWishService.getThingWishList("user2")).thenReturn(new ArrayList<>());
        when(thingService.getDefaultThingList()).thenReturn(allThings);

        APIResponse response = controller.personalized("user2", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);
        assertEquals("热门推荐", items.get(0).getReason());
    }

    // ========== 个性化推荐测试 ==========

    @Test
    void testActiveUser_getsPersonalizedRecommendations() {
        // 3个收藏（Java分类100），达到交互阈值
        List<Map> collectList = new ArrayList<>();
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("2"));

        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user3")).thenReturn(collectList);
        when(orderService.getUserOrderList("user3", null)).thenReturn(new ArrayList<>());
        when(thingWishService.getThingWishList("user3")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedThingIds("user3")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedClassificationIds("user3")).thenReturn(new ArrayList<>());

        APIResponse response = controller.personalized("user3", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);
        assertFalse(items.isEmpty());

        // Java分类的资源应排在前面
        RecommendItem first = items.get(0);
        assertEquals(100L, first.getThing().classificationId);
        assertEquals("同类收藏较多", first.getReason());
    }

    // ========== 已购过滤测试 ==========

    @Test
    void testPurchasedThingsFiltered() {
        List<Map> collectList = new ArrayList<>();
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("2"));
        collectList.add(createCollectMap("3"));

        // 用户已购买thing 1和2
        List<Order> orders = new ArrayList<>();
        orders.add(createOrder("1"));
        orders.add(createOrder("2"));

        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user4")).thenReturn(collectList);
        when(orderService.getUserOrderList("user4", null)).thenReturn(orders);
        when(thingWishService.getThingWishList("user4")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedThingIds("user4")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedClassificationIds("user4")).thenReturn(new ArrayList<>());

        APIResponse response = controller.personalized("user4", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);

        // 已购买的thing 1和2不应出现
        for (RecommendItem item : items) {
            assertNotEquals(1L, (long) item.getThing().getId());
            assertNotEquals(2L, (long) item.getThing().getId());
        }
    }

    // ========== 屏蔽测试 ==========

    @Test
    void testBlockedThingExcluded() {
        List<Map> collectList = new ArrayList<>();
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("2"));
        collectList.add(createCollectMap("3"));

        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user5")).thenReturn(collectList);
        when(orderService.getUserOrderList("user5", null)).thenReturn(new ArrayList<>());
        when(thingWishService.getThingWishList("user5")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedThingIds("user5")).thenReturn(Arrays.asList("3"));
        when(recommendFeedbackService.getBlockedClassificationIds("user5")).thenReturn(new ArrayList<>());

        APIResponse response = controller.personalized("user5", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();

        // thing 3应被排除
        for (RecommendItem item : items) {
            assertNotEquals(3L, (long) item.getThing().getId());
        }
    }

    @Test
    void testBlockedCategoryWeightReduced() {
        List<Map> collectList = new ArrayList<>();
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("3"));
        collectList.add(createCollectMap("5"));

        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user6")).thenReturn(collectList);
        when(orderService.getUserOrderList("user6", null)).thenReturn(new ArrayList<>());
        when(thingWishService.getThingWishList("user6")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedThingIds("user6")).thenReturn(new ArrayList<>());
        // 屏蔽Python分类(200)
        when(recommendFeedbackService.getBlockedClassificationIds("user6")).thenReturn(Arrays.asList(200L));

        APIResponse response = controller.personalized("user6", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);

        // Python分类的资源分数应较低，非屏蔽分类资源应排在前面
        if (items.size() >= 2) {
            RecommendItem first = items.get(0);
            assertNotEquals(200L, (long) first.getThing().classificationId);
        }
    }

    // ========== 不感兴趣反馈测试 ==========

    @Test
    void testNotInterested_savesFeedbackAndLog() {
        Thing thing = createThing(1L, "Java入门", 100L, "100", "5");
        when(thingService.getThingById("1")).thenReturn(thing);

        APIResponse response = controller.notInterested("user1", "1");

        assertEquals(200, response.getCode());
        verify(recommendFeedbackService).block("user1", "1", 100L);
        verify(recommendLogService).log("user1", "1", "block", "不感兴趣");
    }

    @Test
    void testNotInterested_invalidParams() {
        APIResponse response1 = controller.notInterested(null, "1");
        assertEquals(400, response1.getCode());

        APIResponse response2 = controller.notInterested("user1", null);
        assertEquals(400, response2.getCode());

        APIResponse response3 = controller.notInterested("", "");
        assertEquals(400, response3.getCode());
    }

    // ========== 统计测试 ==========

    @Test
    void testStats_returnsCorrectCounts() {
        List<Map<String, Object>> statsDetail = new ArrayList<>();
        Map<String, Object> exposeStat = new HashMap<>();
        exposeStat.put("type", "expose");
        exposeStat.put("count", 100);
        statsDetail.add(exposeStat);
        Map<String, Object> clickStat = new HashMap<>();
        clickStat.put("type", "click");
        clickStat.put("count", 30);
        statsDetail.add(clickStat);
        Map<String, Object> blockStat = new HashMap<>();
        blockStat.put("type", "block");
        blockStat.put("count", 5);
        statsDetail.add(blockStat);

        when(recommendLogService.getStats()).thenReturn(statsDetail);
        when(recommendLogService.countByType("expose")).thenReturn(100);
        when(recommendLogService.countByType("click")).thenReturn(30);
        when(recommendLogService.countByType("block")).thenReturn(5);

        APIResponse response = controller.stats();

        assertEquals(200, response.getCode());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(100, data.get("exposeCount"));
        assertEquals(30, data.get("clickCount"));
        assertEquals(5, data.get("blockCount"));
        assertNotNull(data.get("detail"));
    }

    @Test
    void testStats_emptyData() {
        when(recommendLogService.getStats()).thenReturn(new ArrayList<>());
        when(recommendLogService.countByType("expose")).thenReturn(0);
        when(recommendLogService.countByType("click")).thenReturn(0);
        when(recommendLogService.countByType("block")).thenReturn(0);

        APIResponse response = controller.stats();

        assertEquals(200, response.getCode());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(0, data.get("exposeCount"));
        assertEquals(0, data.get("clickCount"));
        assertEquals(0, data.get("blockCount"));
    }

    // ========== 点击日志测试 ==========

    @Test
    void testClick_logsCorrectly() {
        APIResponse response = controller.click("user1", "1", "personalized");

        assertEquals(200, response.getCode());
        verify(recommendLogService).log("user1", "1", "click", "personalized");
    }

    // ========== 边界情况测试 ==========

    @Test
    void testPersonalized_noUserId_returnsFail() {
        APIResponse response = controller.personalized(null, "10");
        assertEquals(400, response.getCode());

        APIResponse response2 = controller.personalized("", "10");
        assertEquals(400, response2.getCode());
    }

    @Test
    void testPersonalized_noThings_returnsEmptyList() {
        when(thingService.getThingList(null, null, null, null)).thenReturn(new ArrayList<>());

        APIResponse response = controller.personalized("user1", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    void testPersonalized_allPurchased_returnsEmpty() {
        List<Map> collectList = new ArrayList<>();
        collectList.add(createCollectMap("1"));
        collectList.add(createCollectMap("2"));
        collectList.add(createCollectMap("3"));

        // 全部已购
        List<Order> orders = new ArrayList<>();
        for (Thing t : allThings) {
            orders.add(createOrder(String.valueOf(t.getId())));
        }

        when(thingService.getThingList(null, null, null, null)).thenReturn(allThings);
        when(thingCollectService.getThingCollectList("user7")).thenReturn(collectList);
        when(orderService.getUserOrderList("user7", null)).thenReturn(orders);
        when(thingWishService.getThingWishList("user7")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedThingIds("user7")).thenReturn(new ArrayList<>());
        when(recommendFeedbackService.getBlockedClassificationIds("user7")).thenReturn(new ArrayList<>());

        APIResponse response = controller.personalized("user7", "10");

        assertEquals(200, response.getCode());
        List<RecommendItem> items = (List<RecommendItem>) response.getData();
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    // ========== 辅助方法 ==========

    private Map createCollectMap(String thingId) {
        Map<String, Object> map = new HashMap<>();
        map.put("thing_id", thingId);
        return map;
    }

    private Order createOrder(String thingId) {
        Order order = new Order();
        order.setThingId(thingId);
        order.setUserId("user1");
        order.setStatus("1");
        return order;
    }
}
