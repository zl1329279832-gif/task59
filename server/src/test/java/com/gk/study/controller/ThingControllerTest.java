package com.gk.study.controller;

import com.gk.study.common.APIResponse;
import com.gk.study.common.ResponeCode;
import com.gk.study.entity.Order;
import com.gk.study.entity.RecEntity;
import com.gk.study.entity.Recommend;
import com.gk.study.entity.Thing;
import com.gk.study.entity.User;
import com.gk.study.entity.UserCF;
import com.gk.study.service.OrderService;
import com.gk.study.service.RecordService;
import com.gk.study.service.ThingService;
import com.gk.study.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ThingController.recommend 单元测试
 *
 * 覆盖场景：
 * 1. 空行为用户（冷启动）
 * 2. 已购过滤
 * 3. 下架过滤
 * 4. 重复推荐去重
 * 5. 访问记录异常
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ThingControllerTest {

    @InjectMocks
    private ThingController controller;

    @Mock
    private ThingService service;

    @Mock
    private RecordService recordService;

    @Mock
    private OrderService orderService;

    @Mock
    private UserService userService;

    @Mock
    private HttpServletRequest request;

    private Thing thing1;
    private Thing thing2;
    private Thing thing3;
    private Thing thing4;
    private Thing thing5;

    @BeforeEach
    public void setUp() {
        thing1 = createThing(1L, "Java入门", "0", "100");
        thing2 = createThing(2L, "Python进阶", "0", "80");
        thing3 = createThing(3L, "已下架课程", "1", "60");  // 已下架
        thing4 = createThing(4L, "算法基础", "0", "50");
        thing5 = createThing(5L, "数据结构", "0", "40");
    }

    // ==================== 冷启动测试 ====================

    /**
     * 空行为用户：没有任何浏览记录（IP列表为空）
     * 应返回默认热门列表，不抛异常
     */
    @Test
    public void testRecommend_coldStart_noRecords() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing4, thing5));
        mockAnonymousRequest("192.168.1.100");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertNotNull(result);
        assertFalse(result.isEmpty());
        verify(service).getDefaultThingList();
    }

    /**
     * IP列表为null时（数据库空表），不应NPE
     */
    @Test
    public void testRecommend_coldStart_nullIpList() {
        when(recordService.getRecordIpList()).thenReturn(null);
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2));
        mockAnonymousRequest("10.0.0.1");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertNotNull(result);
        assertEquals(2, result.size());
    }

    /**
     * 只有一个用户的浏览记录，不满足协同过滤条件，走默认列表
     */
    @Test
    public void testRecommend_coldStart_singleUser() {
        when(recordService.getRecordIpList()).thenReturn(Collections.singletonList("192.168.1.1"));
        com.gk.study.entity.Record r = new com.gk.study.entity.Record(1L, 3);
        r.setIp("192.168.1.1");
        when(recordService.getRecordListByIp("192.168.1.1")).thenReturn(Collections.singletonList(r));
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing4));
        mockAnonymousRequest("192.168.1.1");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertNotNull(result);
        assertEquals(3, result.size());
    }

    // ==================== 已购过滤测试 ====================

    /**
     * 登录用户已购买的资源不应出现在推荐列表中
     */
    @Test
    public void testRecommend_loggedIn_purchasedFiltered() {
        // 冷启动走默认列表
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        when(service.getDefaultThingList()).thenReturn(
                new ArrayList<>(Arrays.asList(thing1, thing2, thing4, thing5)));

        // 模拟登录用户
        User user = new User();
        user.setId("user123");
        when(request.getHeader("TOKEN")).thenReturn("valid_token");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("WL-Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.50");
        when(userService.getUserByToken("valid_token")).thenReturn(user);

        // 用户已购买thing1和thing4
        Order o1 = new Order();
        o1.setThingId("1");
        Order o4 = new Order();
        o4.setThingId("4");
        when(orderService.getUserOrderList("user123", null)).thenReturn(Arrays.asList(o1, o4));

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        // thing1和thing4已购买，不应出现
        for (Thing t : result) {
            assertNotEquals(1L, (long) t.getId(), "已购资源thing1不应出现");
            assertNotEquals(4L, (long) t.getId(), "已购资源thing4不应出现");
        }
        assertEquals(2, result.size());
    }

    /**
     * 匿名用户（无TOKEN）不做已购过滤，应正常返回
     */
    @Test
    public void testRecommend_anonymous_noPurchaseFilter() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing4, thing5));
        mockAnonymousRequest("192.168.1.200");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertEquals(4, result.size());
        // 不应调用UserService或OrderService
        verify(userService, never()).getUserByToken(anyString());
        verify(orderService, never()).getUserOrderList(anyString(), any());
    }

    // ==================== 下架过滤测试 ====================

    /**
     * 默认列表不应包含已下架资源（status != "0"）
     * 由ThingServiceImpl的status过滤保证，这里验证控制器正确使用了过滤后的列表
     */
    @Test
    public void testRecommend_unlistedThingsFiltered() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        // 模拟service层已过滤下架资源，不返回thing3（status="1"）
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2, thing4, thing5));
        mockAnonymousRequest("192.168.1.100");

        APIResponse response = controller.recommend(request);

        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        for (Thing t : result) {
            assertEquals("0", t.getStatus(), "不应包含已下架资源");
        }
        // thing3（已下架）不在结果中
        boolean thing3Present = result.stream().anyMatch(t -> t.getId() == 3L);
        assertFalse(thing3Present, "已下架的thing3不应出现在推荐中");
    }

    /**
     * 协同过滤路径：推荐ID对应的资源已下架时，service层会过滤
     */
    @Test
    public void testRecommend_cfPath_unlistedFiltered() {
        // 设置两个用户的浏览记录，触发CF路径
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("ip1", "ip2"));

        com.gk.study.entity.Record r1a = new com.gk.study.entity.Record(1L, 5);
        r1a.setIp("ip1");
        com.gk.study.entity.Record r1b = new com.gk.study.entity.Record(3L, 3); // thing3已下架
        r1b.setIp("ip1");
        when(recordService.getRecordListByIp("ip1")).thenReturn(Arrays.asList(r1a, r1b));

        com.gk.study.entity.Record r2a = new com.gk.study.entity.Record(1L, 4);
        r2a.setIp("ip2");
        com.gk.study.entity.Record r2b = new com.gk.study.entity.Record(2L, 3);
        r2b.setIp("ip2");
        com.gk.study.entity.Record r2c = new com.gk.study.entity.Record(3L, 2); // thing3已下架
        r2c.setIp("ip2");
        when(recordService.getRecordListByIp("ip2")).thenReturn(Arrays.asList(r2a, r2b, r2c));

        // 当前用户是ip1，CF应推荐ip2看过但ip1没看过的：thing2
        // service层只返回status="0"的
        when(service.getThingListByThingIds(anyList())).thenReturn(
                Collections.singletonList(thing2));
        mockAnonymousRequest("ip1");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        // 结果中不应包含已下架的thing3
        for (Thing t : result) {
            assertNotEquals(3L, (long) t.getId(), "已下架的thing3不应出现");
        }
    }

    // ==================== 重复推荐去重测试 ====================

    /**
     * 协同过滤结果中重复的thingId应被去重
     */
    @Test
    public void testRecommend_duplicateThingIds_deduplicated() {
        // 设置3个用户触发CF
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("ip1", "ip2", "ip3"));

        // ip1: 只看了thing1
        com.gk.study.entity.Record r1 = new com.gk.study.entity.Record(1L, 5);
        r1.setIp("ip1");
        when(recordService.getRecordListByIp("ip1")).thenReturn(Collections.singletonList(r1));

        // ip2: 看了thing1和thing2
        com.gk.study.entity.Record r2a = new com.gk.study.entity.Record(1L, 4);
        r2a.setIp("ip2");
        com.gk.study.entity.Record r2b = new com.gk.study.entity.Record(2L, 3);
        r2b.setIp("ip2");
        when(recordService.getRecordListByIp("ip2")).thenReturn(Arrays.asList(r2a, r2b));

        // ip3: 看了thing1和thing4
        com.gk.study.entity.Record r3a = new com.gk.study.entity.Record(1L, 3);
        r3a.setIp("ip3");
        com.gk.study.entity.Record r3b = new com.gk.study.entity.Record(4L, 2);
        r3b.setIp("ip3");
        when(recordService.getRecordListByIp("ip3")).thenReturn(Arrays.asList(r3a, r3b));

        // CF推荐结果会经过去重
        when(service.getThingListByThingIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            // 验证传给service的ID列表没有重复
            Set<Long> idSet = new HashSet<>(ids);
            assertEquals(idSet.size(), ids.size(), "传给service的thingId列表不应有重复");
            List<Thing> things = new ArrayList<>();
            for (Long id : ids) {
                if (id == 2L) things.add(thing2);
                if (id == 4L) things.add(thing4);
            }
            return things;
        });
        mockAnonymousRequest("ip1");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        // 验证结果中没有重复
        Set<Long> resultIds = result.stream().map(Thing::getId).collect(Collectors.toSet());
        assertEquals(resultIds.size(), result.size(), "推荐结果不应有重复thingId");
    }

    // ==================== 访问记录异常测试 ====================

    /**
     * 某个IP的浏览记录返回null时应跳过，不影响其他用户
     */
    @Test
    public void testRecommend_recordListByIpReturnsNull() {
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("ip1", "ip2"));

        // ip1返回null
        when(recordService.getRecordListByIp("ip1")).thenReturn(null);

        // ip2有正常记录
        com.gk.study.entity.Record r = new com.gk.study.entity.Record(1L, 5);
        r.setIp("ip2");
        when(recordService.getRecordListByIp("ip2")).thenReturn(Collections.singletonList(r));

        // 只有1个有效用户，走默认列表
        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2));
        mockAnonymousRequest("ip3");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
    }

    /**
     * 某个IP的浏览记录返回空列表时应跳过
     */
    @Test
    public void testRecommend_recordListByIpReturnsEmpty() {
        when(recordService.getRecordIpList()).thenReturn(Arrays.asList("ip1", "ip2"));

        when(recordService.getRecordListByIp("ip1")).thenReturn(Collections.emptyList());
        when(recordService.getRecordListByIp("ip2")).thenReturn(Collections.emptyList());

        when(service.getDefaultThingList()).thenReturn(Arrays.asList(thing1, thing2));
        mockAnonymousRequest("ip3");

        APIResponse response = controller.recommend(request);

        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        // 所有IP记录为空，走默认
        assertNotNull(result);
        assertEquals(2, result.size());
    }

    /**
     * getUserByToken异常时不应影响推荐结果（降级为不过滤已购）
     */
    @Test
    public void testRecommend_userServiceException_gracefulDegradation() {
        when(recordService.getRecordIpList()).thenReturn(Collections.emptyList());
        when(service.getDefaultThingList()).thenReturn(
                new ArrayList<>(Arrays.asList(thing1, thing2, thing4)));

        when(request.getHeader("TOKEN")).thenReturn("bad_token");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("WL-Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(userService.getUserByToken("bad_token")).thenThrow(new RuntimeException("DB连接失败"));

        APIResponse response = controller.recommend(request);

        // 即使token查询异常，仍应返回推荐结果
        assertEquals(ResponeCode.SUCCESS.getCode(), response.getCode());
        @SuppressWarnings("unchecked")
        List<Thing> result = (List<Thing>) response.getData();
        assertEquals(3, result.size());
    }

    // ==================== Recommend算法NPE测试 ====================

    /**
     * CF算法：无邻居时不应NPE，返回空列表
     */
    @Test
    public void testRecommend_cfEmptyDistances_returnsEmptyList() {
        Recommend recommend = new Recommend();
        // 用户列表中不包含目标IP，distances为空
        UserCF u1 = new UserCF("ip1");
        u1.set(1L, 5);
        UserCF u2 = new UserCF("ip2");
        u2.set(2L, 3);

        List<RecEntity> result = recommend.recommend("unknown_ip", Arrays.asList(u1, u2));
        assertNotNull(result, "空邻居时不应返回null");
    }

    /**
     * CF算法：用户列表为空时不应NPE
     */
    @Test
    public void testRecommend_cfEmptyUsers_returnsEmptyList() {
        Recommend recommend = new Recommend();
        List<RecEntity> result = recommend.recommend("ip1", new ArrayList<>());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * CF算法：正常场景应返回邻居看过但自己没看过的物品
     */
    @Test
    public void testRecommend_cfNormalScenario() {
        Recommend recommend = new Recommend();

        UserCF u1 = new UserCF("ip1");
        u1.set(1L, 5);
        u1.set(2L, 3);

        UserCF u2 = new UserCF("ip2");
        u2.set(1L, 4);
        u2.set(2L, 2);
        u2.set(3L, 5);

        List<RecEntity> result = recommend.recommend("ip1", Arrays.asList(u1, u2));
        // ip2是ip1的最近邻，ip2看过thing3但ip1没看过
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(r -> r.thingId == 3L),
                "应推荐邻居看过但自己没看过的物品");
        // 不应推荐自己已看过的
        assertFalse(result.stream().anyMatch(r -> r.thingId == 1L),
                "不应推荐自己已看过的物品");
        assertFalse(result.stream().anyMatch(r -> r.thingId == 2L),
                "不应推荐自己已看过的物品");
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

    /**
     * 模拟匿名请求（无TOKEN头）
     */
    private void mockAnonymousRequest(String ip) {
        when(request.getHeader("TOKEN")).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("WL-Proxy-Client-IP")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(ip);
    }
}
