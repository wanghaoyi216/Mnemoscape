# Mnemoscape 项目经验沉淀库

> 本文档是项目级经验积累，每次任务结束后持续更新。
> 最后更新：2026-05-30

---

## 一、项目架构概览

### 1.1 微服务架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         前端 (Vue3 SPA)                         │
│                      localhost:5173 / :80                        │
└───────────────────────────────┬─────────────────────────────────┘
                                │ HTTP (REST + WebSocket)
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    API Gateway (Spring Cloud)                    │
│                   localhost:9999 / :8080                        │
│            路由 + 鉴权 + 限流 + 跨域                             │
└───────┬───────┬───────┬───────┬───────┬───────┬────────┬────────┘
        │       │       │       │       │       │        │
        ▼       ▼       ▼       ▼       ▼       ▼        ▼
   ┌─────────┬───────┬───────┬─────┬─────────┬────────┬────────┐
   │  Auth   │Memory │  AI   │Asset│Resonance│  公共  │Common  │
   │Service  │Service│Service│     │ Service │  模块  │        │
   │  :8081  │ :8082 │ :8083 │:8084│  :8085  │        │        │
   └────┬────┴───┬───┴───┬───┴─┬──┴────┬────┴────────┴────────┘
        │        │       │     │       │
        ▼        ▼       ▼     ▼       ▼
   ┌─────────┬───────┬──────────┬──────────┐
   │ MySQL   │ Redis │  MinIO   │  Milvus  │
   │         │       │ (对象存储)│ (向量存储)│
   └─────────┴───────┴──────────┴──────────┘
```

### 1.2 服务通信模式

| 通信方式 | 使用场景 | 技术栈 |
|---------|---------|--------|
| 同步REST | 大部分场景 | Spring Cloud OpenFeign |
| 异步消息 | 事件通知 | RabbitMQ |
| 同步WebSocket | 共鸣空间实时通信 | Spring WebSocket |
| 服务内缓存 | 高频读取 | Spring Cache + Redis |

### 1.3 技术栈汇总

**后端：**
- Java 17
- Spring Boot 3.2.5
- Spring Cloud 2023.0.3
- Spring Cloud Alibaba 2023.0.1.0
- Spring AI 1.0.0-M4
- MySQL 8.0
- Redis 7.x
- MinIO (S3兼容对象存储)
- Milvus (向量数据库)
- RabbitMQ (消息队列)

**前端：**
- Vue 3.5 + Composition API
- TypeScript 5.6
- Pinia (状态管理)
- Vue Router 4
- Vue I18n 9
- Vite 5
- Three.js 0.160 (3D渲染)
- MapLibre GL 5 + deck.gl 9 (时空地图)
- ECharts 5 (图表)
- Axios (HTTP客户端)

---

## 二、微服务详细设计经验

### 2.1 Auth Service (认证服务)

**职责：** 用户注册登录、JWT令牌管理、好友关系、3D头像生成

**核心API：**
- `POST /auth/login` - 登录
- `POST /auth/register` - 注册
- `POST /auth/refresh` - 刷新令牌
- `GET /users/me/profile` - 获取用户资料
- `GET /users/search` - 搜索用户
- `GET /friends` - 获取好友列表
- `POST /friends/request` - 发送好友请求
- `PUT /friends/accept/{id}` - 接受好友请求
- `DELETE /friends/{id}` - 删除好友
- `GET /users/me/avatar-profile` - 获取3D头像
- `POST /users/me/avatar-profile` - 创建/更新3D头像
- `GET /users/{userId}/avatar-profile` - 获取他人公开头像

**数据库表：**
- `users` - 用户表
- `friendships` - 好友关系表
- `friend_requests` - 好友请求表
- `avatar_profiles` - 3D头像表

**调用下游：**
- 无（独立认证服务）

**被上游调用：**
- memory-service（获取用户信息、验证好友关系）
- resonance-service（获取用户信息）

**经验教训：**
- JWT的JTI需要写入Redis黑名单实现真正的登出
- 头像生成是耗时操作（5-15秒），需要长超时设置
- 好友关系查询需要考虑性能，大列表时需要分页

### 2.2 Memory Service (记忆服务)

**职责：** 记忆CRUD、时间漂移计算、碎片管理、时空地图数据

**核心API：**
- `GET /memories` - 记忆列表（支持分页、筛选）
- `POST /memories` - 创建记忆
- `GET /memories/{id}` - 获取记忆详情
- `PUT /memories/{id}` - 更新记忆
- `DELETE /memories/{id}` - 删除记忆
- `POST /memories/{id}/lock` - 锁定记忆
- `DELETE /memories/{id}/lock` - 解锁记忆
- `GET /memories/{id}/drift` - 获取漂移状态
- `GET /memories/{id}/versions` - 获取版本历史
- `POST /memories/{id}/restore/{versionNumber}` - 恢复版本
- `GET /memories/{id}/fragments` - 获取记忆碎片
- `GET /memories/route` - 获取时间序轨迹
- `GET /users/me/location` - 获取用户当前位置近似
- `GET /atlas/others` - 获取他人记忆网络（脱敏）
- 管理端统计API（见admin模块）

**数据库表：**
- `memories` - 记忆主表
- `memory_versions` - 版本历史表
- `memory_fragments` - 记忆碎片表
- `location_anchors` - 地理位置锚点表（用于Geocoding）

**调用下游：**
- auth-service（获取用户资料、验证好友关系）
- ai-service（调用Reconstruct服务生成场景）

**被上游调用：**
- api-gateway（路由到用户请求）
- resonance-service（共鸣检索时拉取记忆数据）

**重要设计决策：**
- 漂移计算基于艾宾浩斯遗忘曲线
- Geocoding支持锚点表精确匹配 + 模糊文本匹配
- FRIENDS隐私级别**当前未完全实现**（等同于PRIVATE）

**经验教训：**
- 地理编码是外部API调用，需要超时和降级处理
- 版本历史的changeDescription当前是硬编码英文常量
- visualData为null时有三级兜底策略

### 2.3 AI Service (AI服务)

**职责：** 对话生成、场景重建、意图识别、实体提取、向量索引

**核心API：**
- `POST /chat` - 同步对话
- `POST /chat/stream` - 流式对话（SSE）
- `POST /reconstruct` - 场景重建
- `POST /reconstruct/enhance` - 场景增强
- `POST /reconstruct/fill-gaps` - 场景填充
- `POST /entity/extract` - 实体提取
- `POST /intent/hints` - 意图提示生成
- 向量索引管理API

**调用下游：**
- memory-service（获取记忆上下文）
- asset-service（获取媒体资源）
- resonance-service（共鸣相关AI计算）
- 外部NVIDIA Integrate API（MiniMax M2.7模型）

**外部依赖：**
- NVIDIA Integrate API (MiniMax M2.7)
- Qwen3.5-VL / Kimi-K2.5（视觉前置模型）
- Milvus（向量存储）

**重要设计决策：**
- Reconstruct采用LLM优先 + 规则版兜底的双轨策略
- 流式对话使用SSE协议
- 工具调用通过Spring AI的@Bean FunctionCallback机制

**当前已知问题：**
- Spring AI 1.0.0-M4不直接暴露tool_start/tool_end到SSE
- 多模态支持受限于MiniMax M2.7（不支持图片/音频输入）
- MilvusSearchTool实际是关键词加权模拟，非真实向量检索

**经验教训：**
- 场景重建耗时长（10-30秒），前端需要60秒超时
- ChatClient.stream()返回的是Flux<String>，需要手动包装SSE
- 向量检索的降级策略很重要，Milvus不可用时要能透明降级

### 2.4 Resonance Service (共鸣服务)

**职责：** 记忆共鸣检索、共鸣空间、实时通信、客服系统

**核心API：**
- `GET /resonances/search` - 搜索共鸣记忆
- `POST /resonances/spaces` - 创建共鸣空间
- `GET /resonances/spaces/{id}` - 获取共鸣空间
- `GET /resonances/spaces/{id}/notes` - 获取共鸣便签
- `POST /resonances/spaces/{id}/notes` - 放置便签
- WebSocket端点（共鸣空间实时通信）
- 客服工单API

**数据库表：**
- `resonance_spaces` - 共鸣空间表
- `memory_notes` - 便签表
- `chat_groups` - 聊天群组表
- `chat_messages` - 聊天消息表
- `chat_group_members` - 群组成员表
- `support_tickets` - 客服工单表
- `support_messages` - 客服消息表

**调用下游：**
- memory-service（获取记忆数据计算相似度）
- ai-service（可选：用于真实向量共鸣）
- auth-service（获取用户信息）

**WebSocket端点：**
- `/ws/chat` - 聊天WebSocket
- `/ws/resonance` - 共鸣空间WebSocket
- `/ws/support` - 客服WebSocket

**重要设计决策：**
- 搜索从纯mock升级到关键词加权打分
- 真实向量检索（Milvus）作为可选增强

**经验教训：**
- WebSocket连接需要心跳保活
- 便签的mood字段需要前端i18n翻译
- 共鸣空间的sceneDataUrl可能为空

### 2.5 Asset Service (资源服务)

**职责：** 文件上传下载、静态资源管理、MinIO对象存储、本地资源监控

**核心API：**
- `POST /assets/upload` - 上传文件
- `GET /assets/download/{objectName}` - 下载文件
- `GET /assets/{objectName}/url` - 获取预签名URL
- `DELETE /assets/{objectName}` - 删除文件
- `GET /assets/static/resources` - 获取静态资源列表
- `GET /assets/static/{type}/{filename}` - 获取本地静态文件

**存储结构：**
- 用户上传：`users/{userId}/{filename}`
- 公共素材：无前缀
- 旧上传（需迁移）：无前缀的孤儿对象

**调用下游：**
- 无

**被上游调用：**
- ai-service（获取媒体资源）
- 前端（直接调用）

**重要设计决策：**
- 用户上传对象必须加`users/{userId}/`前缀实现隔离
- 公共素材不带前缀
- LocalResourceWatcher支持热加载`resource/`目录

**当前问题：**
- 旧上传对象（无前缀）会被错误当成公共素材
- MinIO不可达时降级使用blob URL

**经验教训：**
- 预签名URL的签名参数必须完整传递
- 删除操作需要验证所有权
- 本地资源监控需要防止路径穿越攻击

### 2.6 API Gateway

**职责：** 路由转发、认证校验、限流、跨域

**路由配置：**
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/v1/auth/**
            - Path=/api/v1/users/**
            - Path=/api/v1/friends/**
        - id: memory-service
          uri: http://localhost:8082
          predicates:
            - Path=/api/v1/memories/**
            - Path=/api/v1/atlas/**
        - id: ai-service
          uri: http://localhost:8083
          predicates:
            - Path=/api/v1/chat/**
            - Path=/api/v1/reconstruct/**
            - Path=/api/v1/entity/**
            - Path=/api/v1/intent/**
            - Path=/api/v1/vector/**
        - id: resonance-service
          uri: http://localhost:8085
          predicates:
            - Path=/api/v1/resonances/**
            - Path=/api/v1/support/**
        - id: asset-service
          uri: http://localhost:8084
          predicates:
            - Path=/api/v1/assets/**
```

---

## 三、数据库设计规范

### 3.1 表命名规范

| 类型 | 前缀 | 示例 |
|------|------|------|
| 业务表 | 无 | memories, users |
| 关联表 | 无 | friendships |
| 版本表 | xxx_versions | memory_versions |
| 碎片表 | xxx_fragments | memory_fragments |

### 3.2 字段命名规范

| 字段类型 | 命名 | 示例 |
|---------|------|------|
| 主键 | id | id VARCHAR(36) |
| 外键 | xxx_id | user_id VARCHAR(36) |
| 创建时间 | created_at | created_at TIMESTAMP |
| 更新时间 | updated_at | updated_at TIMESTAMP |
| 软删除 | deleted_at | deleted_at TIMESTAMP |
| 状态 | status | status VARCHAR(20) |

### 3.3 索引设计原则

- 主键自动有唯一索引
- 频繁查询条件字段加索引
- 联合索引遵循最左前缀原则
- 避免过多索引（影响写入性能）
- 定期分析慢查询优化索引

### 3.4 各服务数据库表清单

**auth-service:**
- users (id, username, email, password_hash, role, verified, created_at, updated_at)
- friendships (id, user_id, friend_id, status, created_at)
- friend_requests (id, from_user_id, to_user_id, status, created_at, updated_at)
- avatar_profiles (id, user_id, self_description, avatar_traits, emotion_tone, personality_tags, avatar_title, avatar_story, is_public, created_at, updated_at)

**memory-service:**
- memories (id, user_id, title, description, privacy_level, is_locked, fade_level, memory_year, memory_date, memory_season, memory_time_of_day, memory_location, memory_lng, memory_lat, scene_data_url, visual_data, emotion_profile, created_at, updated_at)
- memory_versions (id, memory_id, version_number, title, description, change_description, changed_by, created_at)
- memory_fragments (id, memory_id, fragment_type, content, position_x, position_y, position_z, is_discovered, discovered_at, created_at)
- location_anchors (id, name, lng, lat, type, created_at)

**resonance-service:**
- resonance_spaces (id, memory_a_id, memory_b_id, resonance_score, scene_data_url, status, created_at, updated_at)
- memory_notes (id, resonance_space_id, user_id, content, mood, position_x, position_y, created_at)
- chat_groups (id, type, name, created_at, updated_at)
- chat_messages (id, group_id, sender_id, content, message_type, created_at)
- chat_group_members (id, group_id, user_id, joined_at)
- support_tickets (id, user_id, subject, description, status, priority, assigned_admin_id, client_type, created_at, updated_at, last_message_at)
- support_messages (id, ticket_id, sender_id, sender_role, content, message_type, file_name, file_size, read_at, created_at)

**asset-service:** (使用MinIO对象存储，数据库仅存元数据)

---

## 四、缓存设计规范

### 4.1 缓存策略选择

| 场景 | 策略 | TTL | 更新方式 |
|------|------|-----|---------|
| 用户会话 | Redis String | JWT过期时间 | 登录时设置，logout时删除 |
| 用户资料 | Redis Hash | 30分钟 | Cache-Aside |
| 资源列表 | 本地缓存 | 5分钟 | 定时刷新 |
| 好友列表 | Redis Set | 10分钟 | Cache-Aside |

### 4.2 缓存Key命名规范

```
{service}:{entity}:{identifier}:{field}

示例:
auth:user:profile:12345
auth:friends:list:12345
memory:drift:67890
```

### 4.3 缓存穿透防护

- 空值缓存：对于不存在的数据，缓存一个特殊标记
- 布隆过滤器：用于判断key是否可能存在

### 4.4 缓存击穿防护

- 互斥锁：使用Redisson的互斥锁
- 逻辑过期：数据永不过期，异步更新

### 4.5 缓存雪崩防护

- 随机TTL：在基础TTL上加随机偏移
- 多级缓存：本地缓存 + Redis
- 熔断降级：Redis不可用时降级到数据库

---

## 五、消息队列设计规范

### 5.1 MQ使用场景

当前项目中MQ使用较少，主要是建议在以下场景使用：

| 场景 | 建议使用 | 原因 |
|------|---------|------|
| 异步通知 | RabbitMQ | 解耦、可靠消息 |
| 事件驱动 | RabbitMQ | 事务消息支持 |
| 任务队列 | XXL-Job | 分布式任务调度 |

### 5.2 消息设计规范

```json
{
    "messageId": "UUID-格式",
    "timestamp": "ISO8601",
    "type": "消息类型-枚举",
    "payload": {
        // 业务数据
    },
    "metadata": {
        "userId": "发送者",
        "traceId": "追踪ID"
    }
}
```

### 5.3 消费幂等性设计

- 每个消息携带唯一messageId
- 消费者使用messageId做幂等检查
- 可以使用Redis Set记录已处理的消息ID

---

## 六、定时任务设计规范

### 6.1 定时任务清单

| 任务名 | 触发时间 | 功能 | 并发控制 |
|--------|---------|------|---------|
| LocalResourceWatcher | 文件变更时 | 监控本地资源变更 | 无 |
| (其他待添加) | | | |

### 6.2 任务调度方案

建议使用XXL-Job进行分布式任务调度：
- 支持任务分片
- 支持任务依赖
- 支持任务失败重试
- 支持任务告警

### 6.3 任务执行规范

- 所有任务必须有超时设置
- 所有任务必须有日志记录
- 所有任务失败必须发送告警
- 所有任务必须支持手动触发

---

## 七、踩坑记录与解决方案

### 7.1 后端问题

| 问题 | 原因 | 解决方案 | 预防措施 |
|------|------|---------|---------|
| FRIENDS权限无效 | checkAccess中FRIENDS未调用好友API | 需实现好友关系验证 | 添加单元测试 |
| 版本描述英文 | 硬编码常量 | 改用i18n key | 代码审查 |
| 场景重建内容不匹配 | LLM+规则版双轨问题 | 规则版作为兜底 | 明确降级策略 |
| 工具调用不稳定 | Spring AI版本限制 | 记录日志监控 | 等待Spring AI升级 |
| Milvus降级失败 | 降级逻辑不完善 | 补充关键词加权 | 测试降级路径 |

### 7.2 前端问题

| 问题 | 原因 | 解决方案 | 预防措施 |
|------|------|---------|---------|
| i18n缺失 | 中英文混排 | 补全翻译key | lint检查 |
| intentHints写死 | 无动态API | 需AI服务支持 | 需求评审 |
| fakeEmotion | 无情绪聚合接口 | 需AI服务实现 | 需求评审 |
| 上传降级体验差 | blob URL | 优化降级提示 | 用户体验测试 |

### 7.3 运维问题

| 问题 | 原因 | 解决方案 | 预防措施 |
|------|------|---------|---------|
| MinIO历史残留 | 用户隔离前上传 | 迁移脚本+手动处理 | 数据迁移流程 |
| 依赖服务不可达 | 网络/Docker | 健康检查+告警 | 监控告警 |
| 联机Banner误触发 | 业务异常当基础设施异常 | 区分异常类型 | 错误分类处理 |

---

## 八、代码模板库

### 8.1 后端Controller模板

```java
@RestController
@RequestMapping("/api/v1/xxx")
@RequiredArgsConstructor
@Slf4j
public class XxxController {

    private final XxxService xxxService;

    @GetMapping
    public ApiResponse<PageResult<XxxVO>> listXxx(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(xxxService.listXxx(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<XxxVO> getXxx(@PathVariable String id) {
        return ApiResponse.success(xxxService.getXxx(id));
    }

    @PostMapping
    public ApiResponse<XxxVO> createXxx(@Valid @RequestBody CreateXxxRequest request) {
        return ApiResponse.success(xxxService.createXxx(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<XxxVO> updateXxx(@PathVariable String id,
                                        @Valid @RequestBody UpdateXxxRequest request) {
        return ApiResponse.success(xxxService.updateXxx(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteXxx(@PathVariable String id) {
        xxxService.deleteXxx(id);
        return ApiResponse.success(null);
    }
}
```

### 8.2 后端Service模板

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class XxxService {

    private final XxxRepository xxxRepository;
    private final XxxClient xxxClient; // Feign调用

    @Transactional(rollbackFor = Exception.class)
    public XxxVO createXxx(CreateXxxRequest request) {
        // 1. 参数校验
        validateRequest(request);
        
        // 2. 业务逻辑
        XxxEntity entity = toEntity(request);
        entity = xxxRepository.save(entity);
        
        // 3. 异步通知（如需要）
        // eventPublisher.publishEvent(new XxxCreatedEvent(entity));
        
        return toVO(entity);
    }

    @Cacheable(value = "xxx", key = "#id")
    public XxxVO getXxx(String id) {
        return xxxRepository.findById(id)
                .map(this::toVO)
                .orElseThrow(() -> new BizException(404, "XXX不存在"));
    }

    public PageResult<XxxVO> listXxx(int page, int size, String keyword) {
        Pageable pageable = PageRequest.of(page, size);
        Page<XxxEntity> entityPage = xxxRepository.findByKeyword(keyword, pageable);
        return PageResult.of(entityPage.map(this::toVO));
    }

    private void validateRequest(CreateXxxRequest request) {
        // 校验逻辑
    }

    private XxxEntity toEntity(CreateXxxRequest request) {
        // 转换逻辑
    }

    private XxxVO toVO(XxxEntity entity) {
        // 转换逻辑
    }
}
```

### 8.3 前端API模块模板

```typescript
// src/api/xxx.ts
import client from './client'
import type { ApiResponse, PageResult } from '../types'

export interface XxxItem {
  id: string
  // ...
}

export interface CreateXxxBody {
  // ...
}

export interface UpdateXxxBody {
  // ...
}

export function listXxx(params: { page?: number; size?: number; keyword?: string } = {}) {
  return client.get<ApiResponse<PageResult<XxxItem>>>('/xxx', { params })
}

export function getXxx(id: string) {
  return client.get<ApiResponse<XxxItem>>(`/xxx/${id}`)
}

export function createXxx(data: CreateXxxBody) {
  return client.post<ApiResponse<XxxItem>>('/xxx', data)
}

export function updateXxx(id: string, data: UpdateXxxBody) {
  return client.put<ApiResponse<XxxItem>>(`/xxx/${id}`, data)
}

export function deleteXxx(id: string) {
  return client.delete<ApiResponse<void>>(`/xxx/${id}`)
}
```

### 8.4 前端Pinia Store模板

```typescript
// src/stores/xxx.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { XxxItem } from '@/api/xxx'
import * as api from '@/api/xxx'

export const useXxxStore = defineStore('xxx', () => {
  const items = ref<XxxItem[]>([])
  const current = ref<XxxItem | null>(null)
  const loading = ref(false)

  async function fetchList(params?: Parameters<typeof api.listXxx>[0]) {
    loading.value = true
    try {
      const { data } = await api.listXxx(params)
      items.value = data.data.content
      return data.data
    } finally {
      loading.value = false
    }
  }

  async function fetchOne(id: string) {
    const { data } = await api.getXxx(id)
    current.value = data.data
    return data.data
  }

  async function create(data: Parameters<typeof api.createXxx>[0]) {
    const { data: result } = await api.createXxx(data)
    items.value.push(result.data)
    return result.data
  }

  async function update(id: string, data: Parameters<typeof api.updateXxx>[1]) {
    const { data: result } = await api.updateXxx(id, data)
    const index = items.value.findIndex(item => item.id === id)
    if (index !== -1) {
      items.value[index] = result.data
    }
    return result.data
  }

  async function remove(id: string) {
    await api.deleteXxx(id)
    items.value = items.value.filter(item => item.id !== id)
  }

  return {
    items,
    current,
    loading,
    fetchList,
    fetchOne,
    create,
    update,
    remove,
  }
})
```

### 8.5 前端View模板

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useXxxStore } from '@/stores/xxx'
import { useToastStore } from '@/stores/toast'

const { t } = useI18n()
const xxxStore = useXxxStore()
const toastStore = useToastStore()

const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    await xxxStore.fetchList()
  } catch (e: any) {
    toastStore.push({ key: 'error.fetchFailed', tone: 'error' })
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page-shell">
    <section class="section-card">
      <h2 class="section-title">{{ t('xxx.title') }}</h2>
      
      <div v-if="loading" class="loading">
        {{ t('common.loading') }}
      </div>
      
      <div v-else-if="xxxStore.items.length === 0" class="empty-state">
        <p>{{ t('xxx.empty') }}</p>
      </div>
      
      <div v-else class="card-grid">
        <!-- 列表内容 -->
      </div>
    </section>
  </div>
</template>
```

---

## 九、项目级检查清单

### 9.1 功能开发前检查

- [ ] 已分析现有代码和架构
- [ ] 已设计数据库变更
- [ ] 已设计接口文档
- [ ] 已分析微服务调用
- [ [ ] 已识别风险和依赖
- [ ] 已产出计划文档

### 9.2 后端开发检查

- [ ] 遵循Controller/Service/Repository分层
- [ ] 参数校验使用@Valid + JSR-303
- [ ] 异常处理使用BizException
- [ ] 日志记录使用slf4j
- [ ] 数据库操作使用@Transactional
- [ ] Feign调用配置Fallback
- [ ] 缓存使用@Cacheable
- [ ] 单元测试覆盖核心逻辑

### 9.3 前端开发检查

- [ ] API调用统一使用client实例
- [ ] 状态管理使用Pinia
- [ ] 错误处理使用toast提示
- [ ] i18n key覆盖所有文本
- [ ] 响应式处理loading/error/empty状态
- [ ] 敏感操作二次确认

### 9.4 测试检查

- [ ] 单元测试覆盖Service层
- [ ] 接口测试覆盖Controller层
- [ ] 前端组件测试（如有）
- [ ] E2E测试关键流程（如有）

### 9.5 文档检查

- [ ] 代码注释完整
- [ ] 接口文档更新
- [ ] README更新（如需要）
- [ ] cache-memory进度文档更新

### 9.6 高级组件优化实践

- **RAG 超时熔断最佳实践**：在高维向量检索（如 Milvus / Milvus REST API）中，必须建立非阻塞式的物理状态机。即使在初始化（`ensureCollection`）通过后，读写/检索的 `catch` 块中一旦捕获网络或服务超时 IOException，必须**瞬间完成 `available = false` 的主动短路置位**，以便接下来的后续请求可以在 1ms 内瞬间降级 fallback 到本地轻量关键词打分。这对于保持大语言模型 SSE 流式输出的首字延迟极其关键。
- **微交互与 AI 动效的渐进体验**：在异步 API（如结合大模型推荐提示气泡 hints）检索的等待期（1.5s 左右），使用**毛玻璃流光扫光的骨架屏（Shimmer Skeleton Capsules）**作为视觉掩护，能极大地缓解用户的等待焦虑，并赋予 Mascot 浮窗以拟人化的“思考中”感官暗示。
- **多维度 ECharts 数据流畅变形（Morphing）**：当图表在天、周、月、年等多维度聚合状态间高速切换时，不应该重新创建 Chart 实例。通过合理使用 ECharts 默认自带的系列演化，配合 `animationDuration` 和缓动曲线，可以让面积面积图和折线柔和形变，视觉体验高级。
- **历史数据治理的异步隔离与事务幂等**：在大规模数据重构与清洗（如历史 Fragments 重建）中，由于 AI 重建过程伴随着较长的时延（15-30s），不能采用传统的同步事务阻塞整个 HTTP 管道。通过 `@Async` 与 Spring 内部自代理机制（`asyncEnrichmentSelf`）相结合，主线程将筛选过的待重建 IDs 秒级下发，线程池后台逐条执行物理删除与 AI 重建写入。在单个异步任务中采用最小化 `@Transactional` 隔离，如果单条出错则仅回滚单条记忆，确保历史脏数据治理是幂等且容错的。
- **长时延管理接口的 Timeout 宽限机制**：在管理维护面板的 API 设计中，由于批量处理成百上千条记忆的重绘、坐标回填耗时较长，前端 Axios 必须配置自定义的宽超时阈值（如 `300,000ms`），防止前端默认 15s 抛出 `ECONNABORTED` 网络挂断误报，确保批量任务的成功下发与状态呈现。

---

**文档版本历史：**
| 版本 | 日期 | 修改内容 | 修改人 |
|------|------|---------|--------|
| v1.0 | 2026-05-30 | 初始版本，沉淀项目架构和经验 | AI Employee |
| v1.1 | 2026-05-30 | 追加沉淀了向量自动熔断、AI 骨架流光、大屏 ECharts 切换等高级设计经验 | AI Employee |
| v1.2 | 2026-05-30 | 追加沉淀了历史数据批量重建异步隔离、事务幂等与宽时延接口控制等最佳实践经验 | AI Employee |
