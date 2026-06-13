# 推荐系统架构文档

> 本文档梳理系统中并存的两套推荐接口的设计、差异、已知矛盾及迁移方案。

---

## 目录

1. [架构总览：两套推荐系统](#1-架构总览两套推荐系统)
2. [对比矩阵：IP 协同过滤 vs 登录用户个性化](#2-对比矩阵ip-协同过滤-vs-登录用户个性化)
3. [旧系统：IP 协同过滤推荐（ThingController）](#3-旧系统ip-协同过滤推荐thingcontroller)
4. [新系统：登录用户个性化推荐（RecommendationController）](#4-新系统登录用户个性化推荐recommendationcontroller)
5. [buildCategoryPreference 设计矛盾分析](#5-buildcategorypreference-设计矛盾分析)
6. [RecommendFeedback / RecommendLog 与统计指标](#6-recommendfeedback--recommendlog-与统计指标)
7. [数据库表结构说明](#7-数据库表结构说明)
8. [三处口径对齐（doc.md / readme.md / recommend.vue）](#8-三处口径对齐docmd--readmemd--recommendvue)
9. [前端迁移到 /recommendation/personalized 集成指南](#9-前端迁移到-recommendationpersonalized-集成指南)

---

## 1. 架构总览：两套推荐系统

系统中目前并存两套独立的推荐机制：

```
                   ┌─────────────────────────────────────────┐
                   │           前端 recommend.vue            │
                   │     当前调用: GET /api/thing/recommend   │
                   │     待迁移到: GET /api/recommendation/   │
                   │               personalized              │
                   └──────────┬──────────────┬───────────────┘
                              │              │
               ┌──────────────▼──┐    ┌──────▼────────────────┐
               │  旧: IP 协同过滤 │    │ 新: 用户个性化推荐     │
               │ ThingController │    │ RecommendationController│
               │  /thing/recommend│    │ /recommendation/*      │
               ├─────────────────┤    ├────────────────────────┤
               │ 标识: IP 地址    │    │ 标识: userId (登录态)  │
               │ 算法: Pearson CF │    │ 算法: 分类偏好加权打分  │
               │ 数据: b_record   │    │ 数据: 收藏/心愿/订单/  │
               │                 │    │      评论 + feedback   │
               │ 无反馈闭环      │    │ 曝光/点击/屏蔽闭环     │
               └─────────────────┘    └────────────────────────┘
```

**结论**：旧系统为纯 IP 协同过滤，适用于匿名用户冷启动；新系统为登录用户个性化推荐，支持反馈闭环。产品应以新系统为主，旧系统作为未登录时的降级方案。

---

## 2. 对比矩阵：IP 协同过滤 vs 登录用户个性化

| 维度 | 旧系统（IP 协同过滤） | 新系统（用户个性化） |
|------|----------------------|---------------------|
| **接口路径** | `GET /thing/recommend` | `GET /recommendation/personalized` |
| **用户标识** | 客户端 IP（`IpUtils.getIpAddr`） | `userId` 参数（登录态） |
| **算法类型** | User-based 协同过滤（Pearson 相关系数） | 基于分类偏好的内容推荐 + 热度加权 |
| **输入信号** | `b_record` 表的浏览记录（IP + thingId + score） | 收藏(3.0) / 心愿(2.0) / 订单(2.5) / 评论(1.5) |
| **冷启动策略** | 仅 1 个 IP 时降级为按 PV 排序 | 信号数 < 3 时按 PV 热度推荐 |
| **已购过滤** | 登录时过滤（检查 TOKEN header） | 始终过滤（通过 userId 查订单） |
| **反馈机制** | 无 | 不感兴趣（item 级屏蔽 + 分类降权） |
| **日志记录** | 无 | 曝光 / 点击 / 屏蔽三类事件日志 |
| **统计指标** | 无 | CTR（点击率）、屏蔽率 |
| **响应格式** | `List<Thing>` | `List<RecommendItem>`（含 reason + score） |
| **前端集成** | `recommend.vue` 当前使用 | 无前端集成（待迁移） |
| **是否需要登录** | 否（IP 自动获取） | 是（必须传 userId） |
| **代码位置** | `ThingController.recommend()` + `Recommend.java` | `RecommendationController.personalized()` |

### 响应格式差异

**旧系统**响应（`/thing/recommend`）：

```json
{
  "code": 0,
  "msg": "查询成功",
  "data": [
    {
      "id": 1,
      "title": "羽毛球馆A",
      "cover": "xxx.jpg",
      "price": "50",
      "pv": "120",
      "classificationId": 3
      // ... Thing 的全部字段
    }
  ]
}
```

**新系统**响应（`/recommendation/personalized`）：

```json
{
  "code": 0,
  "msg": "查询成功",
  "data": [
    {
      "thing": {
        "id": 1,
        "title": "羽毛球馆A",
        "cover": "xxx.jpg",
        "price": "50",
        "pv": "120",
        "classificationId": 3
      },
      "reason": "同类收藏较多",
      "score": 8.5
    }
  ]
}
```

> 关键区别：新系统将 Thing 包装在 `RecommendItem` 中，额外返回 `reason`（推荐理由）和 `score`（得分），前端迁移时需要用 `item.thing.xxx` 代替 `item.xxx` 来访问资源属性。

---

## 3. 旧系统：IP 协同过滤推荐（ThingController）

### 3.1 数据采集

在 `ThingController.detail()` 中，每次用户查看场馆详情页时自动记录：

```java
// ThingController.java:64-81
String ip = IpUtils.getIpAddr(request);
Record record = recordService.getRecord(thing.getId(), ip);
if (record != null) {
    record.setScore(record.getScore() + 1);  // 累加浏览次数
    recordService.updateRecord(record);
} else {
    Record entity = new Record();
    entity.setThingId(thing.getId());
    entity.setIp(ip);
    entity.setScore(1);                       // 首次浏览 score=1
    recordService.createRecord(entity);
}
```

数据存储在 `b_record` 表，以 `(ip, thing_id)` 为粒度，`score` 表示浏览次数。

### 3.2 推荐算法流程

```
1. 获取所有 IP 列表 ──→ SELECT ip FROM b_record GROUP BY ip
2. 为每个 IP 构建 UserCF 对象 ──→ { ip, List<RecEntity(thingId, score)> }
3. 判断用户数
   ├── <= 1：降级为默认列表（按 PV 降序）
   └── > 1：进入协同过滤
       ├── 计算当前 IP 与所有其他 IP 的 Pearson 相关系数
       ├── 找到最近邻（相关系数最高的 IP）
       ├── 取最近邻浏览过但当前用户未浏览的资源
       └── 按 score 降序排序返回
4. 如果推荐结果为空 ──→ 降级为默认列表
```

### 3.3 Pearson 相关系数公式

`Recommend.java` 中的 `pearson_dis` 方法实现：

```
r = (sum(xy) - sum(x)*sum(y)/n) / sqrt((sum(x^2) - sum(x)^2/n) * (sum(y^2) - sum(y)^2/n))
```

其中 `x` 和 `y` 分别为两个 IP 的浏览评分向量。

### 3.4 局限性

- **IP 不等于用户**：同一 NAT/代理后的多个用户被视为同一用户；同一用户切换网络被视为不同用户
- **无负反馈**：没有"不感兴趣"机制
- **无日志**：无法追踪推荐效果
- **冷启动粗糙**：仅靠用户数 <= 1 判断，而非行为量

---

## 4. 新系统：登录用户个性化推荐（RecommendationController）

### 4.1 API 接口列表

| HTTP 方法 | 路径 | 权限 | 描述 |
|-----------|------|------|------|
| GET | `/recommendation/personalized` | 无（需传 userId） | 获取个性化推荐列表 |
| POST | `/recommendation/notInterested` | 无（需传 userId + thingId） | 标记"不感兴趣" |
| POST | `/recommendation/click` | 无（需传 userId + thingId） | 记录点击事件 |
| GET | `/recommendation/stats` | ADMIN | 管理员统计概览 |

### 4.2 个性化推荐流程

```
personalized(userId, size)
│
├── 1. 获取屏蔽列表
│   ├── blockedThingIds ──→ 已屏蔽的资源 ID 集合
│   └── blockedClassificationIds ──→ 已屏蔽的分类 ID 集合
│
├── 2. 获取已购列表 ──→ purchasedThingIds
│
├── 3. 构建分类偏好 ──→ buildCategoryPreference()
│   ├── categoryPreference: Map<classificationId, score>
│   ├── categoryReason: Map<classificationId, reason>
│   └── totalSignals: 信号总数
│
├── 4. 冷启动判断 ──→ totalSignals < 3 ?
│
├── 5. 获取候选资源 ──→ thingService.getDefaultThingList()（按 PV 降序）
│
├── 6. 逐候选打分
│   ├── 过滤：已购买 → 跳过
│   ├── 过滤：已屏蔽资源 → 跳过
│   ├── 冷启动用户 → score = PV, reason = "热门推荐"
│   └── 活跃用户
│       ├── 分类匹配 → score = 偏好分 + 已购加成(+5.0) - 屏蔽惩罚(*0.3)
│       ├── 无匹配 → score = PV * 0.1, reason = "热门资源"
│       ├── 全局热度加成 → score += PV * 0.05
│       └── 评分加成 → score += rate * 0.5
│
├── 7. 降序排序取 top N
│
└── 8. 记录曝光日志（每个结果一条 exposure 记录）
```

### 4.3 信号权重表

| 信号来源 | 权重 | 推荐理由文案 | 数据来源 |
|----------|------|-------------|----------|
| 收藏 | 3.0 | "同类收藏较多" | `ThingCollectService.getThingCollectList(userId)` |
| 心愿 | 2.0 | "同类心愿较多" | `ThingWishService.getThingWishList(userId)` |
| 订单 | 2.5 | "已购资源的进阶内容" | `OrderService.getUserOrderList(userId, null)` |
| 评论 | 1.5 | "浏览过相近分类" | `CommentService.getUserCommentList(userId)` |
| 浏览记录 | ~~1.0~~ **未使用** | ~~"浏览过相近分类"~~ | `RecordService`（按 IP 查询，无法映射到 userId） |

### 4.4 打分公式（活跃用户）

```
对于每个候选 thing:

if 分类在 categoryPreference 中:
    score = categoryPreference[classificationId]
    if 用户购买过同分类资源:
        score += 5.0
    if 分类在 blockedClassificationIds 中:
        score *= 0.3   // BLOCKED_CATEGORY_PENALTY
else:
    score = PV * 0.1

// 全局调整
score += PV * 0.05     // 热度加成
score += rate * 0.5    // 评分加成
```

### 4.5 关键常量

| 常量 | 值 | 含义 |
|------|-----|------|
| `DEFAULT_RECOMMEND_SIZE` | 10 | 默认返回推荐数量 |
| `COLD_START_THRESHOLD` | 3 | 冷启动信号阈值 |
| `BLOCKED_CATEGORY_PENALTY` | 0.3 | 屏蔽分类权重惩罚系数 |

---

## 5. buildCategoryPreference 设计矛盾分析

### 5.1 核心矛盾：Record 按 IP、Order 按 userId

`buildCategoryPreference` 方法（`RecommendationController.java:295-361`）存在一个已知的设计矛盾：

| 数据源 | 标识键 | 与 userId 的关系 |
|--------|--------|-----------------|
| `b_record`（浏览记录） | **IP 地址** | **无直接映射**——系统中不存在 IP ↔ userId 的关联表 |
| `b_order`（订单） | userId | 直接关联 |
| `b_thing_collect`（收藏） | userId | 直接关联 |
| `b_thing_wish`（心愿） | userId | 直接关联 |
| `b_comment`（评论） | userId | 直接关联 |

### 5.2 代码中的 Workaround

在 `buildCategoryPreference` 方法中，注释明确说明了此矛盾及变通方案：

```java
// --- 浏览信号 (权重 1.0, 基于IP的浏览记录映射) ---
// 由于 RecordService 是按 IP 查询的，此处通过用户评论和订单间接推断浏览偏好
```

**变通方式**：放弃直接使用 `b_record` 的浏览数据，转而通过订单和评论间接推断浏览偏好。逻辑是：用户下单或评论的资源，一定曾经浏览过对应分类。

### 5.3 影响

- **浏览信号丢失**：原本设计的权重 1.0 浏览信号从未被采集，用户的纯浏览行为（未下单、未评论、未收藏）无法影响个性化推荐
- **冷启动灵敏度降低**：冷启动阈值基于 `signals` 计数，但浏览这个高频行为无法贡献信号数，导致实际需要更多显式行为（收藏/下单/评论）才能脱离冷启动
- **偏好矩阵偏斜**：订单权重 2.5 在信号源中被双重计算——一次在"订单信号"中、一次在间接推断的"浏览偏好"中（代码 L333-344），实际效果是订单对偏好的影响被放大

### 5.4 未来改进建议（仅记录，不修改代码）

1. **方案 A — 扩展 b_record 表**：增加 `user_id` 字段，在 `ThingController.detail()` 中同时记录 IP 和 userId（登录时），使 `RecordService` 支持按 userId 查询
2. **方案 B — 建立映射表**：新增 `b_user_ip_mapping` 表，在用户登录时记录 IP-userId 关联，查询时先通过 userId 找到 IP 集合，再查 `b_record`
3. **方案 C — 前端埋点**：在前端浏览事件中同时上报 userId，直接写入新的 `b_user_browse` 表，绕过 IP 机制

---

## 6. RecommendFeedback / RecommendLog 与统计指标

### 6.1 RecommendFeedback — 不感兴趣反馈

**实体类**：`com.gk.study.entity.RecommendFeedback`
**数据表**：`b_recommend_feedback`

功能说明：
- 用户在推荐列表中对某资源标记"不感兴趣"
- 同时记录该资源的 `classificationId`（冗余存储），支持**分类级别降权**
- `(user_id, thing_id)` 唯一约束，防止重复屏蔽

服务接口（`RecommendFeedbackService`）：

| 方法 | 说明 |
|------|------|
| `block(userId, thingId, classificationId)` | 保存屏蔽记录 |
| `getBlockedThingIds(userId)` | 获取用户已屏蔽的资源 ID 列表 |
| `getBlockedClassificationIds(userId)` | 获取用户已屏蔽资源对应的分类 ID 列表 |

推荐算法中的使用：
- **资源级屏蔽**：已屏蔽的 thingId 从候选列表中**完全移除**
- **分类级降权**：已屏蔽分类的候选资源得分乘以 `BLOCKED_CATEGORY_PENALTY`（0.3），但**不移除**

### 6.2 RecommendLog — 推荐事件日志

**实体类**：`com.gk.study.entity.RecommendLog`
**数据表**：`b_recommend_log`

记录推荐系统全生命周期的三类事件：

| 事件类型 (type) | 触发时机 | 触发接口 | reason 字段内容 |
|----------------|----------|----------|----------------|
| `exposure` | 推荐列表返回给前端时 | `GET /recommendation/personalized` | 推荐算法生成的理由（如"同类收藏较多"） |
| `click` | 用户点击推荐资源时 | `POST /recommendation/click` | 前端回传的推荐理由 |
| `block` | 用户标记"不感兴趣"时 | `POST /recommendation/notInterested` | "用户标记不感兴趣" |

### 6.3 统计指标（/recommendation/stats）

**权限**：仅 ADMIN

该接口从 `b_recommend_log` 表按 `type` 字段 GROUP BY 统计，返回：

```json
{
  "code": 0,
  "msg": "查询成功",
  "data": {
    "exposureCount": 1520,
    "clickCount": 203,
    "blockCount": 15,
    "clickRate": "13.36%",
    "blockRate": "0.99%"
  }
}
```

计算公式：

```
CTR (点击率)  = clickCount / exposureCount × 100%
屏蔽率        = blockCount / exposureCount × 100%
```

对应 SQL（`RecommendLogMapper.xml`）：

```sql
SELECT type, COUNT(*) AS count FROM b_recommend_log GROUP BY type
```

---

## 7. 数据库表结构说明

> 本节替代 doc 目录中缺失的《表结构》Word 文件，仅覆盖推荐相关表。

### 7.1 b_record — 浏览记录表（旧协同过滤）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT, PK, AUTO_INCREMENT | 主键 |
| `thing_id` | BIGINT | 浏览的资源 ID |
| `score` | INT | 浏览次数（每次访问详情页 +1） |
| `ip` | VARCHAR | 访问者 IP 地址 |

- **写入时机**：`ThingController.detail()` 每次被调用时
- **读取时机**：`ThingController.recommend()` 中查全量 IP 列表和各 IP 浏览记录
- **索引**：无显式索引（通过 MyBatis-Plus BaseMapper 查询）

### 7.2 b_recommend_log — 推荐日志表（新系统）

```sql
CREATE TABLE IF NOT EXISTS `b_recommend_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     VARCHAR(64)  NOT NULL COMMENT '用户id',
    `thing_id`    VARCHAR(64)  NOT NULL COMMENT '资源id',
    `type`        VARCHAR(20)  NOT NULL COMMENT '事件类型: exposure / click / block',
    `reason`      VARCHAR(255) DEFAULT '' COMMENT '推荐原因',
    `create_time` VARCHAR(32)  DEFAULT NULL COMMENT '事件时间(毫秒时间戳)',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_thing_id` (`thing_id`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐日志表';
```

### 7.3 b_recommend_feedback — 推荐反馈表（新系统）

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

### 7.4 相关业务表（推荐信号来源）

以下表为推荐系统的输入信号来源，表结构不在此重复，仅列出与推荐相关的关键字段：

| 表名 | 推荐系统使用的关键字段 | 信号用途 |
|------|----------------------|----------|
| `b_thing` | `id`, `classification_id`, `pv`, `rate`, `status`, `title`, `cover`, `price` | 候选资源池 + PV/评分加成 |
| `b_thing_collect` | `user_id`, `thing_id` | 收藏信号（权重 3.0） |
| `b_thing_wish` | `user_id`, `thing_id` | 心愿信号（权重 2.0） |
| `b_order` | `user_id`, `thing_id` | 订单信号（权重 2.5）+ 已购过滤 |
| `b_comment` | `user_id`, `thing_id` | 评论信号（权重 1.5） |
| `b_classification` | `id`, `title` | 分类元数据 |

### 7.5 DDL 文件位置

新增表的建表语句：`server/recommendation_tables.sql`

---

## 8. 三处口径对齐（doc.md / readme.md / recommend.vue）

### 8.1 当前各处描述

| 来源 | 当前描述 | 实际状态 |
|------|----------|----------|
| **doc/doc.md** 第 19 行 | "热门推荐：基于协同过滤推荐算法的热门推荐" | 仅描述旧系统，未提及新系统 |
| **readme.md** 第 31 行 | "热门推荐：基于协同过滤推荐算法的热门推荐" | 与 doc.md 完全相同，同样过时 |
| **recommend.vue** | 调用 `GET /api/thing/recommend`（旧系统） | 前端仅接入旧 IP 协同过滤 |
| **doc/doc.md** 代码结构段 | 后端 controller 目录只列出 `ThingController` | 缺少 `RecommendationController` |

### 8.2 口径矛盾

1. doc.md 和 readme.md 声称"基于协同过滤推荐"，但新系统 `RecommendationController` 使用的是**基于分类偏好的内容推荐**，不是协同过滤
2. 前端 `recommend.vue` 调用旧 API，但后端已有功能更完善的新 API
3. doc.md 的代码结构中未列出 `RecommendationController`、`RecommendFeedback`、`RecommendLog` 等新增组件

### 8.3 建议统一口径

**doc.md 功能介绍**建议更新为：

```
- 热门推荐：支持两种推荐模式。未登录时使用基于 IP 的协同过滤推荐；
  登录后使用基于用户行为（收藏、心愿、订单、评论）的个性化推荐，
  支持"不感兴趣"反馈闭环。详见 [推荐系统架构文档](recommendation-architecture.md)。
```

**readme.md** 建议与 doc.md 保持一致。

---

## 9. 前端迁移到 /recommendation/personalized 集成指南

### 9.1 迁移目标

将 `recommend.vue` 从旧的 `/api/thing/recommend` 接口切换到新的 `/api/recommendation/personalized` 接口，获得个性化推荐能力和反馈闭环。

### 9.2 步骤 1：新增 API 定义

在 `web/src/api/thing.js`（或新建 `web/src/api/recommendation.js`）中添加：

```javascript
const recommendationURL = {
  personalized: '/api/recommendation/personalized',
  notInterested: '/api/recommendation/notInterested',
  click: '/api/recommendation/click',
};

// 获取个性化推荐（登录用户）
export const getPersonalizedApi = (params) =>
  get({ url: recommendationURL.personalized, params });

// 标记不感兴趣
export const postNotInterestedApi = (data) =>
  post({ url: recommendationURL.notInterested, data });

// 记录点击
export const postClickApi = (data) =>
  post({ url: recommendationURL.click, data });
```

### 9.3 步骤 2：修改 recommend.vue 数据获取

```javascript
// 原来的调用
getRecommendApi({}, {}).then(res => { ... })

// 改为：根据登录状态选择接口
const userStore = useUserStore();
const userId = userStore.user_id;  // 从 store 获取登录用户 ID

if (userId) {
  // 已登录 → 个性化推荐
  getPersonalizedApi({ userId, size: contentData.pageSize }).then(res => {
    contentData.thingData = [];
    res.data.forEach(item => {
      // 注意：新接口返回 RecommendItem，Thing 在 item.thing 中
      const thing = item.thing;
      if (thing.cover) {
        thing.cover = BASE_URL + '/api/staticfiles/image/' + thing.cover;
      }
      if (thing.status === '0') {
        contentData.thingData.push({
          ...thing,
          _reason: item.reason,   // 保存推荐理由供前端展示
          _score: item.score,
        });
      }
    });
    contentData.total = contentData.thingData.length;
    changePage(1);
  });
} else {
  // 未登录 → 降级到旧 IP 协同过滤
  getRecommendApi({}, {}).then(res => { /* 原有逻辑不变 */ });
}
```

### 9.4 步骤 3：展示推荐理由

在 `recommend.vue` 模板中为已登录用户显示推荐理由：

```html
<div class="info-view">
  <h3 class="thing-name">{{ item.title.substring(0, 12) }}</h3>
  <span v-if="item._reason" class="recommend-reason">{{ item._reason }}</span>
  <span>
    <span class="a-price">{{ item.price }}元</span>
  </span>
</div>
```

### 9.5 步骤 4：接入"不感兴趣"和点击上报

```javascript
// 点击推荐资源时上报
const handleDetail = (item) => {
  if (userId && item._reason) {
    postClickApi({ userId, thingId: item.id, reason: item._reason });
  }
  let text = router.resolve({ name: 'detail', query: { id: item.id } });
  window.open(text.href, '_blank');
};

// 不感兴趣按钮
const handleNotInterested = (item) => {
  postNotInterestedApi({ userId, thingId: item.id }).then(() => {
    // 从当前列表移除
    contentData.thingData = contentData.thingData.filter(t => t.id !== item.id);
    contentData.total = contentData.thingData.length;
    changePage(contentData.page);
  });
};
```

### 9.6 步骤 5：灰度策略（可选）

如果不想一次性全量切换，可以保留双接口并行：

```javascript
// 用 feature flag 控制
const USE_NEW_RECOMMEND = true; // 或从配置中心读取

if (USE_NEW_RECOMMEND && userId) {
  getPersonalizedApi({ userId, size: 20 }).then(handleNewResponse);
} else {
  getRecommendApi({}, {}).then(handleOldResponse);
}
```

### 9.7 注意事项

1. **登录态判断**：新接口要求 `userId` 非空，未登录用户必须降级到旧接口
2. **响应格式变更**：新接口返回 `RecommendItem` 对象，资源信息在 `.thing` 子字段中
3. **旧接口保留**：建议暂不删除 `/thing/recommend`，作为未登录用户的降级方案
4. **建表**：确保已执行 `server/recommendation_tables.sql` 创建 `b_recommend_log` 和 `b_recommend_feedback` 表
