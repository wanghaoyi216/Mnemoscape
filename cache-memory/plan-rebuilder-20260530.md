# 历史 Fragments 批量重建管理入口 (Admin Fragment Rebuilder) 执行计划

> 任务编号：TASK-OPT-REBUILDER-20260530
> 创建日期：2026-05-30
> 任务类型：数据清洗与场景碎片重构
> 优先级：P1
> 预计工时：1.5天
> 执行人：AI Employee (Antigravity)

---

## 一、 任务概述

### 1.1 任务目标
当系统经历早期迭代或在离线规则模式下运行时，产生的 `MemoryFragment` 记录主要是英文写死的 mock 内容（例如 "A forgotten toy half-buried in the warm soil"）。在系统接入真实大模型并支持 grounded 中文碎片重构后，我们需要为系统管理员提供一个**一键数据治理/批量重建**的接口与后台面板，以便一次性将历史 mock 数据清洗干净。

### 1.2 成功标准
- **无损升级**：对系统现有正常记忆无任何副作用，在重新执行 reconstruction 前会彻底清除原有的 mock 碎片。
- **异步安全**：大模型调用接口可能会存在时延（10s-30s），批量操作必须由 Spring `@Async` 异步处理，避免 HTTP 连接阻塞与网关超时（15s）。
- **极客美学界面**：前端 `AdminMaintenanceView.vue` 新增“历史 Fragments 重建”卡片，带流光 Cyberpunk 输入样式，并支持多语言 i18n 无缝切换。
- **双端编译通过**：后端 microservices 编译成功，前端 TS 静态分析无报错。

---

## 二、 详细开发路线

### Phase 1: 详细设计 (Day 1)
- 确认 JpaRepository 的删除与修改事务。
- 输出 `cache-memory/design-rebuilder-20260530.md`，对数据一致性、事务范围及 Feign 超时策略进行分析。

### Phase 2: 后端微服务开发 (Day 1-2)
- 在 `MemoryService.java` 中增加批量逻辑 `rebuildFragments`。
- 在 `MemoryService.java` 中增加异步作业 `runFragmentRebuildAsync` 并添加 `@Async` 注解。
- 在 `AdminMemoryManagementController.java` 中暴露 `POST /rebuild-fragments` 路由。

### Phase 3: 前端页面与 i18n 开发 (Day 2)
- 在 `adminManagement.ts` 中声明 API 端点及超时（5分钟超时）。
- 补全 `en-US.json` 与 `zh-CN.json` 的国际化翻译，特别是补充之前遗漏的 `geo` 翻译并新增 `fragments` 翻译。
- 重构 `AdminMaintenanceView.vue`，加入交互动效与控制。

### Phase 4: 构建自检与集成测试 (Day 2)
- 后端编译检查与 JUnit 单元测试。
- 前端 `vue-tsc` 类型校验。
