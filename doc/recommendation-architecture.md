# 推荐系统架构文档

> 本文档梳理系统中并存的两套推荐机制——**IP 协同过滤**（旧）与**登录用户个性化推荐**（新），
> 明确各自的输入信号、用户标识、响应格式差异，记录已知设计矛盾及代码中的 workaround，
> 并给出从旧接口迁移到 `/recommendation/personalized` 的集成指南。

---

## 1. 两套推荐系统总览

| 维度 | IP 协同过滤（旧，生产在用） | 登录用户个性化推荐（新，仅后端） |
|------|---------------------------|-------------------------------|
| **入口 Controller** | `ThingController.recommend()` | `RecommendationController.personalized()` |
| **接口路径** | `GET /api/thing/recommend` | `GET /recommendation/personalized` |
| **用户标识** | IP 地址（`IpUtils.getIpAddr(request)`） | `userId` 参数（需登录） |
| **算法** | Pearson 相关系数 — 基于 IP 的协同过滤 | 多信号加权分类匹配 + 热度兜底 |
| **数据来源** | 仅浏览记录（`b_record`） | 收藏、心愿、订单、评论（间接推断浏览） |
| **反馈闭环** | 无 | "不感兴趣"屏蔽 + 分类惩罚 |
| **日志埋点** | 无 | 曝光 / 点击 / 屏蔽事件（`b_recommend_log`） |
| **管理后台统计** | 无 | 曝光数、点击数、屏蔽数、CTR、屏蔽率 |
| **可解释性** | 无推荐理由 | 每条结果携带 `reason` 字段 |
| **冷启动策略** | IP 数 ≤ 1 时回退到热门列表 | 行为信号 < 3 时回退到 PV 热门 |
| **已购过滤** | 仅在 TOKEN 头存在时过滤 | 始终过滤已购资源 |
| **前端调用方** | `recommend.vue`、`detail.vue` | **暂无前端接入** |

---

## 2. 接口详细对比

### 2.1 IP 协同过滤 — `GET /api/thing/recommend`

**请求参数：** 无（通过 `HttpServletRequest` 自动获取客户端 IP）

**响应格式：**
```json
{
  "code": 200,
  "msg": "查询成功",
  "data": [
    {
      "id": 1,
      "title": "羽毛球馆A",
      "price": "50",
      "cover": "xxx.jpg",
      "status": "0",
      "classificationId": 2,
      "pv": "1200",
      "rate": "4.5"
    }
  ]
}
```

> 响应直接返回 `Thing` 对象列表，**不含推荐理由和推荐得分**。

**内部流程：**
1. `recordService.getRecordIpList()` 获取所有去重 IP
2. 对每个 IP 加载浏览记录，构造 `UserCF(ip, List<RecEntity>)` 列表
3. 若 IP 数 ≤ 1 → 直接返回 `getDefaultThingList()`（按 PV 降序的热门列表）
4. 否则调用 `Recommend.recommend(currentIp, users)` 执行 Pearson 相关系数计算
5. 找到最相似 IP（最近邻），返回该邻居浏览过但当前用户未浏览的资源
6. 去重 → 查询完整 Thing 对象 → 返回
7. 若请求头含 `TOKEN`，额外过滤已购资源

### 2.2 个性化推荐 — `GET /recommendation/personalized`

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | String | 是 | 登录用户 ID |
| `size` | String | 否 | 返回数量，默认 10 |

**响应格式：**
```json
{
  "code": 200,
  "msg": "查询成功",
  "data": [
    {
      "thing": {
        "id": 1,
        "title": "羽毛球馆A",
        "price": "50",
        "cover": "xxx.jpg"
      },
      "reason": "同类收藏较多",
      "score": 12.5
    }
  ]
}
```

> 响应返回 `RecommendItem` 对象列表，包含嵌套的 `thing` 对象、`reason` 推荐理由和 `score` 排序得分。
> **与旧接口的关键区别**：`data[]` 下不是直接的 Thing，而是 `{ thing, reason, score }` 结构。

**错误响应：**
```json
{ "code": 1, "msg": "userId 不能为空" }
```

### 2.3 其他新接口

#### `POST /recommendation/notInterested` — 标记不感兴趣

| 参数 | 类型 | 必填 |
|------|------|------|
| `userId` | String | 是 |
| `thingId` | String | 是 |

响应：`{ "code": 200, "msg": "已标记为不感兴趣" }`

#### `POST /recommendation/click` — 记录点击

| 参数 | 类型 | 必填 |
|------|------|------|
| `userId` | String | 是 |
| `thingId` | String | 是 |
| `reason` | String | 否（前端回传推荐理由） |

响应：`{ "code": 200, "msg": "记录成功" }`

#### `GET /recommendation/stats` — 管理员统计

> 需要 `@Access(level = AccessLevel.ADMIN)` 权限

响应：
```json
{
  "code": 200,
  "msg": "查询成功",
  "data": {
    "exposureCount": 1000,
    "clickCount": 150,
    "blockCount": 30,
    "clickRate": "15.00%",
    "blockRate": "3.00%"
  }
}
```

---

## 3. `buildCategoryPreference` 设计矛盾与 Workaround

### 3.1 核心矛盾

`buildCategoryPreference()` 位于 `RecommendationController.java`（第 295–361 行），
负责根据用户行为构建分类偏好映射 `Map<classificationId, score>`。

**设计矛盾**：浏览记录表 `b_record` 以 **IP 地址**作为标识存储，而新推荐系统以 **userId** 作为标识。
`RecordService` 只提供 `getRecordListByIp(ip)` 方法，无法按 userId 查询浏览历史。

代码中的注释明确标注了这个 workaround（第 330–332 行）：

```java
// --- 浏览信号 (权重 1.0, 基于IP的浏览记录映射) ---
// 由于 RecordService 是按 IP 查询的，此处通过用户评论和订单间接推断浏览偏好
// 补充：通过用户订单获取浏览过的分类
```

### 3.2 实际采用的四路信号

由于无法直接使用浏览记录，`buildCategoryPreference` 使用以下四种间接信号：

| 信号来源 | Service 方法 | 权重 | 推荐理由文本 | 标识 |
|---------|-------------|------|------------|------|
| 收藏 | `thingCollectService.getThingCollectList(userId)` | 3.0 | "同类收藏较多" | userId |
| 心愿 | `thingWishService.getThingWishList(userId)` | 2.0 | "同类心愿较多" | userId |
| 订单 | `orderService.getUserOrderList(userId, null)` | 2.5 | "已购资源的进阶内容" | userId |
| 评论 | `commentService.getUserCommentList(userId)` | 1.5 | "浏览过相近分类" | userId |

**信号累加逻辑**：对每条行为，查找对应 Thing 的 `classificationId`，
使用 `Map.merge(cid, weight, Double::sum)` 按分类累加得分。
推荐理由使用 `putIfAbsent`，即首个命中的信号决定展示文案。

### 3.3 冷启动判定

方法返回 `int signals`（行为信号总数），与常量 `COLD_START_THRESHOLD = 3` 比较：
- `signals < 3` → 冷启动用户，退化为 PV 热门推荐，理由显示 "热门推荐"
- `signals >= 3` → 正常个性化推荐

### 3.4 后续改进建议（仅文档记录，不改代码）

- 若要真正使用浏览信号，需将 `b_record` 表增加 `user_id` 列，
  或在用户登录后追加一条按 userId 记录的浏览日志
- 当前订单信号权重 2.5 承担了"浏览+购买"双重语义，
  如果未来接入真实浏览记录，需要拆分权重

---

## 4. 数据库表结构说明

> 以下替代 doc 文件夹中缺失的 Word 表结构文档，仅列出推荐相关的三张表。

### 4.1 `b_record` — 浏览记录表（IP 协同过滤数据源）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT, PK, AUTO_INCREMENT | 主键 |
| `thing_id` | BIGINT | 资源 ID |
| `score` | INT, 默认 0 | 浏览次数（每次访问 +1） |
| `ip` | VARCHAR(64) | 访问者 IP 地址 |

> 该表无索引约束（除主键外），`ip` 和 `thing_id` 无唯一约束，
> 同一 IP + 同一资源的多次浏览通过 `score` 字段累加。
> `RecordService.getRecord(thingId, ip)` 用于查找已有记录以决定是新增还是累加。

### 4.2 `b_recommend_log` — 推荐事件日志表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT, PK, AUTO_INCREMENT | 主键 |
| `user_id` | VARCHAR(64), NOT NULL | 用户 ID |
| `thing_id` | VARCHAR(64), NOT NULL | 资源 ID |
| `type` | VARCHAR(20), NOT NULL | 事件类型（见下方枚举） |
| `reason` | VARCHAR(255), 默认 `''` | 推荐原因快照 |
| `create_time` | VARCHAR(32) | 毫秒时间戳（字符串存储） |

**索引：** `idx_user_id(user_id)`、`idx_thing_id(thing_id)`、`idx_type(type)`

**`type` 枚举值：**

| 值 | 含义 | 触发场景 |
|----|------|---------|
| `exposure` | 曝光 | 推荐接口返回结果时，对每条结果记录 |
| `click` | 点击 | 用户点击推荐结果时，由前端调用 `/recommendation/click` |
| `block` | 屏蔽 | 用户标记"不感兴趣"时，由 `/recommendation/notInterested` 记录 |

**DDL：**
```sql
CREATE TABLE IF NOT EXISTS `b_recommend_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     VARCHAR(64)  NOT NULL COMMENT '用户id',
    `thing_id`    VARCHAR(64)  NOT NULL COMMENT '资源id',
    `type`        VARCHAR(20)  NOT NULL COMMENT '事件类型: exposure(曝光), click(点击), block(屏蔽)',
    `reason`      VARCHAR(255) DEFAULT '' COMMENT '推荐原因',
    `create_time` VARCHAR(32)  DEFAULT NULL COMMENT '事件时间(毫秒时间戳)',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_thing_id` (`thing_id`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐日志表';
```

### 4.3 `b_recommend_feedback` — 推荐反馈表（不感兴趣）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT, PK, AUTO_INCREMENT | 主键 |
| `user_id` | VARCHAR(64), NOT NULL | 用户 ID |
| `thing_id` | VARCHAR(64), NOT NULL | 被屏蔽的资源 ID |
| `classification_id` | BIGINT | 资源所属分类 ID（冗余字段，便于按分类降权） |
| `create_time` | VARCHAR(32) | 毫秒时间戳（字符串存储） |

**索引：** `uk_user_thing(user_id, thing_id)` 唯一约束、`idx_user_id(user_id)`

> `classification_id` 是冗余字段，避免每次降权时回查 `b_thing` 表获取分类。
> 唯一约束 `uk_user_thing` 保证同一用户对同一资源不会重复屏蔽。

**DDL：**
```sql
CREATE TABLE IF NOT EXISTS `b_recommend_feedback` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`           VARCHAR(64)  NOT NULL COMMENT '用户id',
    `thing_id`          VARCHAR(64)  NOT NULL COMMENT '资源id',
    `classification_id` BIGINT       DEFAULT NULL COMMENT '资源所属分类id（冗余，便于按分类降权）',
    `create_time`       VARCHAR(32)  DEFAULT NULL COMMENT '反馈时间(毫秒时间戳)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_thing` (`user_id`, `thing_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐反馈表（不感兴趣）';
```

### 4.4 其他关联表（已有，此处仅列关联关系）

| 表名 | 在推荐中的角色 |
|------|-------------|
| `b_thing` | 资源主表，提供 `classification_id`、`pv`、`rate`、`status` |
| `b_thing_collect` | 收藏表，userId + thingId → 权重 3.0 信号 |
| `b_thing_wish` | 心愿表，userId + thingId → 权重 2.0 信号 |
| `b_order` | 订单表，userId + thingId → 权重 2.5 信号 + 已购过滤 |
| `b_comment` | 评论表，userId + thingId → 权重 1.5 信号 |
| `b_classification` | 分类表，将 Thing 映射到分类以构建偏好 |

---

## 5. RecommendFeedback 与 RecommendLog 服务说明

### 5.1 `RecommendFeedbackService`

| 方法 | 说明 |
|------|------|
| `block(userId, thingId, classificationId)` | 新增屏蔽记录（内部先 `isBlocked` 去重） |
| `getBlockedThingIds(userId)` | 返回用户屏蔽的资源 ID 列表 → **完全排除**这些资源 |
| `getBlockedClassificationIds(userId)` | 返回用户屏蔽的分类 ID 列表 → 对应分类得分 **× 0.3 惩罚** |
| `isBlocked(userId, thingId)` | 检查是否已屏蔽（count 判断） |

**惩罚机制：**
- `blockedThingIds` 中的资源在推荐时直接跳过（score 不参与，不进入结果列表）
- `blockedClassificationIds` 中的分类，得分乘以 `BLOCKED_CATEGORY_PENALTY = 0.3`（即降低 70%）
- 推荐理由追加 `（已降低权重）` 后缀

### 5.2 `RecommendLogService`

| 方法 | 说明 |
|------|------|
| `log(userId, thingId, type, reason)` | 记录一条事件日志 |
| `getStats()` | 返回 `List<Map>` —— 按 type 分组的 COUNT 统计 |
| `countByType(type)` | 返回指定 type 的总条数 |
| `hasClick(userId, thingId)` | 检查用户是否点击过某资源 |

### 5.3 Stats 指标计算

`RecommendationController.stats()` 接口返回的管理员指标：

| 指标 | 计算方式 |
|------|---------|
| `exposureCount` | `SELECT COUNT(*) FROM b_recommend_log WHERE type = 'exposure'` |
| `clickCount` | `SELECT COUNT(*) FROM b_recommend_log WHERE type = 'click'` |
| `blockCount` | `SELECT COUNT(*) FROM b_recommend_log WHERE type = 'block'` |
| `clickRate` | `clickCount / exposureCount * 100`，格式 `"15.00%"` |
| `blockRate` | `blockCount / exposureCount * 100`，格式 `"3.00%"` |

> 底层 SQL 位于 `RecommendLogMapper.xml`：
> `SELECT type, COUNT(*) AS count FROM b_recommend_log GROUP BY type`

---

## 6. 前端口径对齐

### 6.1 当前状态（三处不一致）

| 位置 | 当前描述 | 实际调用 |
|------|---------|---------|
| `readme.md` 第 31 行 | "热门推荐：基于协同过滤推荐算法的热门推荐" | — |
| `doc/doc.md` 第 19 行 | "热门推荐：基于协同过滤推荐算法的热门推荐" | — |
| `web/src/views/index/recommend.vue` | 页面标题"热门推荐"，导航入口同名 | `getRecommendApi` → `GET /api/thing/recommend`（旧 IP-CF） |

三处均只描述了旧的 IP 协同过滤方案，未提及新的个性化推荐接口。

### 6.2 `recommend.vue` 详情

- **路由**：`/index/recommend`，在 `allowList` 中（无需登录即可访问）
- **API 调用**：`getRecommendApi({},{})` → `GET /api/thing/recommend`
- **数据处理**：过滤 `status === '0'` 的资源，拼接封面图 URL，客户端分页（每页 12 条）
- **未接入**：`/recommendation/personalized`、不感兴趣反馈、点击埋点

### 6.3 `detail.vue` 侧栏

- 同样调用 `getRecommendApi`（旧 IP-CF），取前 6 条展示"相关推荐"

---

## 7. 迁移到 `/recommendation/personalized` 集成指南

### 7.1 迁移决策建议

| 场景 | 建议接口 |
|------|---------|
| 未登录用户访问推荐页 | 继续走 `GET /api/thing/recommend`（IP-CF，无需 userId） |
| 已登录用户访问推荐页 | 切换到 `GET /recommendation/personalized?userId=xxx` |
| 详情页侧栏"相关推荐" | 可保持旧接口（侧栏不需要反馈闭环） |

### 7.2 前端迁移步骤

#### Step 1: 新增 API 方法

在 `web/src/api/thing.js` 中新增：

```javascript
// 个性化推荐
const getPersonalizedApi = async (params) =>
  get({ url: '/recommendation/personalized', params: params, headers: {} });

// 标记不感兴趣
const notInterestedApi = async (data) =>
  post({ url: '/recommendation/notInterested', params: data, headers: {} });

// 记录推荐点击
const clickRecommendApi = async (data) =>
  post({ url: '/recommendation/click', params: data, headers: {} });

export { ..., getPersonalizedApi, notInterestedApi, clickRecommendApi };
```

#### Step 2: 改造 `recommend.vue`

核心改动点：

1. **判断登录态**：从 `userStore` 获取 userId
2. **分支调用**：已登录 → `getPersonalizedApi({ userId })`，未登录 → `getRecommendApi()`
3. **适配响应格式差异**：

```javascript
// 旧接口响应：data = [ Thing, Thing, ... ]
// 新接口响应：data = [ { thing: Thing, reason: "...", score: 12.5 }, ... ]

if (isLoggedIn) {
  const res = await getPersonalizedApi({ userId: userStore.userId });
  contentData.thingData = res.data.map(item => ({
    ...item.thing,
    cover: BASE_URL + '/api/staticfiles/image/' + item.thing.cover,
    recommendReason: item.reason  // 可在 UI 上展示推荐理由
  }));
} else {
  const res = await getRecommendApi({}, {});
  contentData.thingData = res.data
    .filter(item => item.status === '0')
    .map(item => ({
      ...item,
      cover: BASE_URL + '/api/staticfiles/image/' + item.cover
    }));
}
```

4. **添加"不感兴趣"按钮**（可选）：

```javascript
const handleNotInterested = async (thingId) => {
  await notInterestedApi({ userId: userStore.userId, thingId });
  // 从列表中移除该资源
  contentData.thingData = contentData.thingData.filter(item => item.id !== thingId);
};
```

5. **添加点击埋点**：

```javascript
const handleDetail = (item) => {
  if (isLoggedIn) {
    clickRecommendApi({ userId: userStore.userId, thingId: item.id, reason: item.recommendReason });
  }
  let text = router.resolve({ name: 'detail', query: { id: item.id } });
  window.open(text.href, '_blank');
};
```

#### Step 3: 路由权限调整

`/index/recommend` 目前在 `allowList` 中（无需登录）。
迁移后仍需保留未登录用户的访问能力（降级到旧接口），因此 **无需修改路由权限**。

#### Step 4: 更新文档口径

- `readme.md`：将"热门推荐"描述改为 "智能推荐：支持 IP 协同过滤（未登录）和登录用户个性化推荐"
- `doc/doc.md`：在"热门推荐功能开发流程"章节追加个性化推荐说明，并添加本文档链接

### 7.3 后端注意事项

1. **`userId` 类型**：当前 `personalized` 接口接收 `String userId`，
   前端传值需与 `b_user.id` 一致
2. **跨域**：`/recommendation/*` 路径已在 `WebMvcConfigurer` 的 CORS 配置范围内（与 `/api/*` 共享）
3. **性能**：`buildCategoryPreference` 对每条行为信号都会调用 `safeGetThing()` 查询数据库，
   行为多的用户可能有 N 次 DB 查询，后续可考虑批量查询优化
4. **`create_time` 存储格式**：毫秒时间戳以 `String` 存储（如 `"1718300000000"`），
   前端展示时需要 `parseInt()` 转换

---

## 8. 代码文件索引

| 文件路径 | 说明 |
|---------|------|
| `server/.../controller/ThingController.java` | 旧 IP-CF 推荐接口（`/api/thing/recommend`） |
| `server/.../controller/RecommendationController.java` | 新个性化推荐接口（`/recommendation/*`） |
| `server/.../entity/Recommend.java` | Pearson 相关系数协同过滤算法实现 |
| `server/.../entity/RecommendItem.java` | 个性化推荐结果 DTO（thing + reason + score） |
| `server/.../entity/Record.java` | 浏览记录实体（`b_record`，IP 标识） |
| `server/.../entity/RecommendFeedback.java` | 不感兴趣反馈实体（`b_recommend_feedback`） |
| `server/.../entity/RecommendLog.java` | 推荐事件日志实体（`b_recommend_log`） |
| `server/.../entity/UserCF.java` | 协同过滤辅助实体（IP → List<RecEntity>） |
| `server/.../entity/RecEntity.java` | 协同过滤辅助实体（thingId + score） |
| `server/.../service/RecordService.java` | 浏览记录服务（按 IP 查询） |
| `server/.../service/RecommendFeedbackService.java` | 屏蔽反馈服务 |
| `server/.../service/RecommendLogService.java` | 推荐日志服务 |
| `server/.../mapper/RecommendLogMapper.xml` | 日志统计 SQL（`GROUP BY type`） |
| `server/recommendation_tables.sql` | `b_recommend_log` 和 `b_recommend_feedback` DDL |
| `server/.../controller/RecommendationControllerTest.java` | 10 个单元测试（冷启动、个性化、屏蔽、统计） |
| `web/src/api/thing.js` | 前端 API 层（仅旧接口） |
| `web/src/views/index/recommend.vue` | 热门推荐页面（调用旧接口） |
| `web/src/views/index/detail.vue` | 详情页侧栏"相关推荐"（调用旧接口） |
