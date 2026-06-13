package com.gk.study.controller;

import com.gk.study.common.APIResponse;
import com.gk.study.common.ResponeCode;
import com.gk.study.entity.Order;
import com.gk.study.entity.Record;
import com.gk.study.entity.Thing;
import com.gk.study.service.OrderService;
import com.gk.study.service.RecordService;
import com.gk.study.service.ThingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ThingController.recommend 接口单元测试
 *
 * 覆盖场景：
 * 1. 冷启动：无记录 / 仅1个用户 → 默认推荐
 * 2. 匿名用户（当前IP不在CF用户列表中） → 默认推荐
 * 3. 下架资源过滤
 * 4. 已购资源过滤
 * 5. 重复 thingId 去重
 * 6. 空行为用户被跳过
 * 7. RecordService 返回 null 不触发 NPE
 */
@ExtendWith(MockitoExtension.class)
public class ThingRecommendTest {

    @InjectMocks
    private ThingController controller;

    @Mock
    private ThingService service;

    @Mock
    private RecordService recordService;

    @Mock
    private OrderService orderService;

    @Mock
    private HttpServletRequest request;

    private Thing thing1;
    private Thing thing2;
    private Thing thing3;
    private Thing thing4;
    private Thing thing5;

    @BeforeEach
    public void setUp() {
        thing1 = createThing(1L, "资源A", "0", "100");
        thing2 = createThing(2L, "资源B", "0", "80");
        thing3 = createThing(3L, "资源C", "0", "60");
        thing4 = createThing(4L, "资源D（已下架）", "1", "50");
        thing5 = createThing(5L, "资源E", "0", "40");
    }

    // ==================== 冷启动测试 ====================

    /**
     * 无任何浏览记录 → getRecordIpList 返回空 → users.size()=0 → 默认推荐
     */
    @Test
    public void testColdStart_noRecords_returnsDefault() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        List<Thing> defaults = Arrays.asList(thing1, thing2, thing3);
        when(service.getDefaultThingList()).thenReturn(defaults);

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertEquals(3, result.size());
        verify(service).getDefaultThingList();
    }

    /**
     * 仅1个IP有记录 → users.size()=1 → 不满足CF条件 → 默认推荐
     */
    @Test
    public void testColdStart_oneUser_returnsDefault() {
        when(recordService.getRecordIpList()).thenReturn(Collections.singletonList("192.168.1.1"));

        Record r1 = new Record(1L, 3);
        r1.setIp("192.168.1.1");
        when(recordService.getRecordListByIp("192.168.1.1")).thenReturn(Collections.singletonList(r1));

        List<Thing> defaults = Arrays.asList(thing1, thing2);
        when(service.getDefaultThingList()).thenReturn(defaults);

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertEquals(2, result.size());
        verify(service).getDefaultThingList();
    }

    // ==================== 匿名用户测试 ====================

    /**
     * 有多个IP满足CF条件，但当前用户IP不在其中 → CF返回空 → 默认推荐
     */
    @Test
    public void testAnonymousUser_noRecordsInCF_returnsDefault() {
        // 两个已有记录的IP
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("10.0.0.1", "10.0.0.2"));

        Record r1 = new Record(1L, 5);
        r1.setIp("10.0.0.1");
        when(recordService.getRecordListByIp("10.0.0.1")).thenReturn(Collections.singletonList(r1));

        Record r2 = new Record(2L, 3);
        r2.setIp("10.0.0.2");
        when(recordService.getRecordListByIp("10.0.0.2")).thenReturn(Collections.singletonList(r2));

        // 当前请求IP是一个全新的IP，不在用户列表中
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("WL-Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("172.16.0.99");

        List<Thing> defaults = Arrays.asList(thing1, thing2, thing3);
        when(service.getDefaultThingList()).thenReturn(defaults);

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertFalse(result.isEmpty(), "匿名用户应获得默认推荐列表");
    }

    // ==================== 下架过滤测试 ====================

    /**
     * 默认列表中混入下架资源（status!="0"），service层已过滤，不应出现在结果中
     */
    @Test
    public void testOffShelfThingsFiltered() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());

        // service 层已按 status="0" 过滤，所以默认列表不包含 thing4（status="1"）
        List<Thing> filtered = Arrays.asList(thing1, thing2, thing3, thing5);
        when(service.getDefaultThingList()).thenReturn(filtered);

        APIResponse response = controller.recommend(request);

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();

        // thing4 (已下架) 不应出现
        boolean offShelfPresent = result.stream().anyMatch(t -> t.getId() == 4L);
        assertFalse(offShelfPresent, "已下架资源不应出现在推荐列表中");

        // 上架资源应保留
        assertEquals(4, result.size());
    }

    // ==================== 已购过滤测试 ====================

    /**
     * 登录用户已购买的资源不应出现在推荐列表中
     */
    @Test
    public void testPurchasedThingsFiltered() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());

        List<Thing> defaults = Arrays.asList(thing1, thing2, thing3);
        when(service.getDefaultThingList()).thenReturn(defaults);

        // 模拟登录用户
        when(request.getParameter("userId")).thenReturn("user_123");

        // 用户已购买 thing2
        Order order = new Order();
        order.setThingId("2");
        when(orderService.getUserOrderList(eq("user_123"), isNull()))
                .thenReturn(Collections.singletonList(order));

        APIResponse response = controller.recommend(request);

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();

        boolean thing2Present = result.stream().anyMatch(t -> t.getId() == 2L);
        assertFalse(thing2Present, "已购买资源不应出现在推荐列表中");

        // thing1 和 thing3 应保留
        assertEquals(2, result.size());
    }

    // ==================== 去重测试 ====================

    /**
     * 当默认列表中出现重复 thingId 时，结果应去重
     */
    @Test
    public void testDuplicateThingIdsDeduped() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());

        // 模拟 service 返回了重复的资源（极端场景）
        Thing thing1Dup = createThing(1L, "资源A-副本", "0", "90");
        List<Thing> withDups = Arrays.asList(thing1, thing2, thing1Dup, thing3);
        when(service.getDefaultThingList()).thenReturn(withDups);

        APIResponse response = controller.recommend(request);

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();

        // 检查 thingId=1 只出现一次
        long thing1Count = result.stream().filter(t -> t.getId() == 1L).count();
        assertEquals(1, thing1Count, "同一个 thingId 只应出现一次");
    }

    // ==================== 空行为用户测试 ====================

    /**
     * 某IP有记录但返回空列表 → 该用户应被跳过，不影响CF计算
     */
    @Test
    public void testEmptyBehaviorUserSkipped() {
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("10.0.0.1", "10.0.0.2", "10.0.0.3"));

        Record r1 = new Record(1L, 5);
        when(recordService.getRecordListByIp("10.0.0.1")).thenReturn(Collections.singletonList(r1));

        // 10.0.0.2 返回空列表
        when(recordService.getRecordListByIp("10.0.0.2")).thenReturn(Collections.emptyList());

        Record r3 = new Record(2L, 3);
        when(recordService.getRecordListByIp("10.0.0.3")).thenReturn(Collections.singletonList(r3));

        // 有效用户只有2个（10.0.0.1 和 10.0.0.3），满足CF条件
        // 但当前IP不在其中 → CF返回空 → 默认推荐
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("WL-Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.0.1");

        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3));

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
    }

    // ==================== Null安全测试 ====================

    /**
     * getRecordIpList 返回 null → 不触发 NPE → 返回默认推荐
     */
    @Test
    public void testRecordServiceReturnsNull_noNPE() {
        when(recordService.getRecordIpList()).thenReturn(null);
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2));

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertEquals(2, result.size());
    }

    /**
     * getRecordListByIp 返回 null → 该IP被跳过 → 不触发 NPE
     */
    @Test
    public void testRecordListByIpReturnsNull_noNPE() {
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("10.0.0.1", "10.0.0.2"));
        when(recordService.getRecordListByIp("10.0.0.1")).thenReturn(null);

        Record r2 = new Record(1L, 2);
        when(recordService.getRecordListByIp("10.0.0.2")).thenReturn(Collections.singletonList(r2));

        // 有效用户只有1个 → 默认推荐
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2));

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
    }

    // ==================== CF正常推荐测试 ====================

    /**
     * 多个活跃用户 + 当前IP在列表中 → CF正常返回推荐结果
     */
    @Test
    public void testCFRecommendation_normalCase() {
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("10.0.0.1", "10.0.0.2", "10.0.0.3"));

        // 用户1: 浏览了 thing1 和 thing2
        Record r1a = new Record(1L, 5);
        Record r1b = new Record(2L, 3);
        when(recordService.getRecordListByIp("10.0.0.1")).thenReturn(Arrays.asList(r1a, r1b));

        // 用户2: 浏览了 thing1 和 thing3
        Record r2a = new Record(1L, 4);
        Record r2b = new Record(3L, 6);
        when(recordService.getRecordListByIp("10.0.0.2")).thenReturn(Arrays.asList(r2a, r2b));

        // 用户3（当前用户）: 浏览了 thing1（没看过thing2和thing3）
        Record r3a = new Record(1L, 2);
        when(recordService.getRecordListByIp("10.0.0.3")).thenReturn(Collections.singletonList(r3a));

        // 当前请求IP为 10.0.0.3
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.3");

        // CF应推荐 thing2 或 thing3（邻居看过但当前用户没看过的）
        when(service.getThingListByThingIds(anyList())).thenReturn(Arrays.asList(thing2, thing3));

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertFalse(result.isEmpty(), "CF推荐应返回结果");
    }

    /**
     * 未传userId参数时不执行已购过滤
     */
    @Test
    public void testNoUserId_skipsPurchasedFilter() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing3));
        when(request.getParameter("userId")).thenReturn(null);

        APIResponse response = controller.recommend(request);

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertEquals(3, result.size(), "无userId时应返回全部默认资源");

        // OrderService 不应被调用
        verify(orderService, never()).getUserOrderList(anyString(), any());
    }

    // ==================== 辅助方法 ====================

    private Thing createThing(Long id, String title, String status, String pv) {
        Thing thing = new Thing();
        thing.setId(id);
        thing.setTitle(title);
        thing.setStatus(status);
        thing.setPv(pv);
        thing.setRate("5");
        thing.setScore("0");
        thing.setWishCount("0");
        thing.setCollectCount("0");
        return thing;
    }
}
