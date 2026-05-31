# Mnemoscape AI员工工作区索引

> 本目录是AI员工的专属工作区，包含所有任务执行过程中产生的文档和模板。

---

## 一、快速导航

### 核心文档（必读）

| 文档 | 说明 | 优先级 |
|------|------|--------|
| [AI-EMPLOYEE-PROMPT-BOOK.md](./AI-EMPLOYEE-PROMPT-BOOK.md) | **AI员工标准工作手册** | ⭐⭐⭐ |
| [AI-EMPLOYEE-STARTER-GUIDE.md](./AI-EMPLOYEE-STARTER-GUIDE.md) | **AI员工启动指南** | ⭐⭐⭐ |
| [project-lessons.md](./project-lessons.md) | **项目经验沉淀库** | ⭐⭐⭐ |

### 模板文档

| 文档 | 说明 | 用途 |
|------|------|------|
| [task-plan-template.md](./task-plan-template.md) | 任务执行计划模板 | 新任务开始时复制使用 |
| [task-summary-template.md](./task-summary-template.md) | 任务总结报告模板 | 任务完成时使用 |
| [progress-tracker-template.md](./progress-tracker-template.md) | 进度追踪模板 | 跟踪任务进度 |
| [problems-and-solutions.md](./problems-and-solutions.md) | 问题记录模板 | 记录执行中的问题 |

### 建议文档

| 文档 | 说明 |
|------|------|
| [optimization-suggestions.md](./optimization-suggestions.md) | 项目优化建议清单 |

---

## 二、文档结构

```
cache-memory/
│
├── 📖 核心文档（必读）
│   ├── AI-EMPLOYEE-PROMPT-BOOK.md      # AI员工工作手册（规范定义）
│   ├── AI-EMPLOYEE-STARTER-GUIDE.md    # 启动指南（快速上手）
│   └── project-lessons.md              # 项目经验（架构知识）
│
├── 📝 模板文档
│   ├── task-plan-template.md           # 执行计划模板
│   ├── task-summary-template.md         # 总结报告模板
│   ├── progress-tracker-template.md     # 进度追踪模板
│   └── problems-and-solutions.md        # 问题记录模板
│
├── 💡 建议文档
│   └── optimization-suggestions.md     # 优化建议清单
│
└── 📂 任务执行记录（按日期/任务组织）
    ├── plan-[任务名]-[日期].md          # 任务执行计划
    ├── design-[任务名]-[日期].md         # 架构设计文档
    ├── backend-progress-[日期].md        # 后端进度记录
    ├── frontend-progress-[日期].md       # 前端进度记录
    ├── integration-[日期].md             # 集成测试记录
    ├── summary-[任务名]-[日期].md        # 任务总结报告
    └── ...
```

---

## 三、工作流程

```
┌─────────────────────────────────────────────────────────────┐
│                     1. 接收任务                             │
│            阅读AI-EMPLOYEE-STARTER-GUIDE.md                 │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  2. 理解并计划                               │
│     阅读project-lessons.md了解项目                            │
│     复制task-plan-template.md创建执行计划                     │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  3. 执行计划                                 │
│     按Phase执行：分析设计→后端→前端→集成→总结                   │
│     每个阶段更新进度，记录问题到problems-and-solutions.md      │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  4. 产出文档                                 │
│     - 执行计划: plan-xxx.md                                 │
│     - 设计文档: design-xxx.md                               │
│     - 进度记录: backend/frontend-progress-xxx.md            │
│     - 问题记录: problems-and-solutions.md（持续更新）         │
│     - 总结报告: summary-xxx.md                              │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  5. 经验沉淀                                 │
│     更新project-lessons.md                                   │
│     更新optimization-suggestions.md                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 四、关键规范速查

### 文档命名
- 日期格式：`YYYYMMDD`（如：`20260530`）
- 任务名：使用英文下划线分隔（如：`friends_privacy`）
- 示例：`plan-friends_privacy-20260530.md`

### 任务优先级
- **P0**：阻塞/核心功能不可用（立即处理）
- **P1**：重要功能缺失（1-2天）
- **P2**：功能改进（1周）
- **P3**：体验优化（2周）

### 禁止事项
1. ❌ 不读项目经验就开工
2. ❌ 不分析就编码
3. ❌ 跳过文档产出
4. ❌ 不测试就提交
5. ❌ 硬编码配置
6. ❌ 忽略安全校验

---

## 五、快速参考

### ReAct + Plan and Execute模式

```
REASON → 我要做什么？我怎么做？
   ↓
ACT → 执行行动
   ↓
OBSERVE → 观察结果
   ↓
REFLECT → 反思是否达到预期
   ↓
(循环直到完成)
```

### 阶段产出
| 阶段 | 产出文档 |
|------|---------|
| Phase 1 | plan-xxx.md, design-xxx.md |
| Phase 2 | backend-progress-xxx.md |
| Phase 3 | frontend-progress-xxx.md |
| Phase 4 | integration-xxx.md |
| Phase 5 | summary-xxx.md, 更新project-lessons.md |

---

**最后更新：** 2026-05-30
**创建人：** AI Employee System
**版本：** v1.0
