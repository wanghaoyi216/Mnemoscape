# Mnemoscape · 忆境星空

> 一个把人生记忆重建为展厅、场景与共鸣网络的 AI 个人记忆博物馆。

[![Vue 3](https://img.shields.io/badge/Vue-3-42b883?logo=vue.js&logoColor=white)](https://vuejs.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6db33f?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Docker Compose](https://img.shields.io/badge/Docker%20Compose-ready-2496ed?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/license-private-lightgrey)](#)

Mnemoscape 把“记录一段经历”变成一次温柔的策展：用户可以写下记忆，生成带有情绪、时间与场景的展品；随后通过时间线、地图、关系图、3D 场景和共鸣空间重新走近它们。项目同时提供 AI 对话入口、记忆漂移、媒体素材、后台管理与可观测性基础设施。

## 视觉预览

当前版本采用“安静的夜间记忆博物馆”方向：墨色画布、低饱和玫瑰、铜金交互色、纸本展签式信息层级。背景画作负责氛围，文字和控件保持克制，避免把每个区域都做成发光的 AI 仪表盘。

![登录页氛围素材](./图/登录页-全局分为背景/12.png)

![记忆与 AI 概念插画](./图/概念插画-记忆与AI主题/72.png)

![暖色记忆展品](./图/情绪记忆封面-暖色调/16.png)

更多界面素材、场景底图、功能插画和视频封面位于 [`图/`](./图/) 与 [`resource/`](./resource/)。前端使用的转换后静态资源位于 [`frontend/public/media/`](./frontend/public/media/)。

## 产品模块

| 模块 | 说明 |
| --- | --- |
| 记忆展厅 | 创建、浏览、搜索和筛选个人记忆 |
| 记忆铸造 | 通过文字、图片和情绪信息构建记忆展品 |
| 时间漫游 | 在时间线上回看记忆的变化与漂移 |
| 关系图谱 | 观察人物、地点、情绪与记忆之间的关系 |
| 记忆地图 / 3D 场景 | 用空间隐喻重新进入记忆 |
| 共鸣空间 | 基于情绪向量和场景相似度发现关联记忆 |
| AI 对话 | 通过流式响应探索、总结和重建记忆 |
| 管理与可观测性 | 用户、记忆、共鸣、健康检查与指标面板 |

## 技术结构

```text
Mnemoscape/
├── frontend/                 Vue 3 + Vite + TypeScript
│   ├── src/views/            业务页面与后台页面
│   ├── src/components/       布局、AI、媒体与通用组件
│   ├── src/assets/           素材目录与设计 Token
│   └── public/media/         前端运行时静态资源
├── backend/
│   ├── api-gateway/          网关与统一鉴权入口
│   ├── auth-service/         用户、JWT 与刷新机制
│   ├── memory-service/       记忆、时间线、图谱与媒体关联
│   ├── ai-service/           ReAct、工具调用与 AI 流式能力
│   ├── resonance-service/    共鸣匹配与聊天能力
│   ├── asset-service/        媒体资产服务
│   └── common/               跨服务安全、异常和通用配置
├── deploy/                   Docker Compose 与部署配置
├── docs/                     设计文档、提示词与验收记录
├── resource/                 原始图片、视频、音频与字体资源
└── scripts/                  本地开发、验证与远程部署脚本
```

## 本地运行

### 前端开发

```bash
cd frontend
npm ci
npm run dev
```

### 前端构建

```bash
cd frontend
npm run build
```

### Docker Compose

```bash
docker compose -f deploy/docker-compose.all.yml up -d --build
```

前端默认访问地址：<http://localhost:5173/login>

停止服务：

```bash
docker compose -f deploy/docker-compose.all.yml down
```

## 环境变量与安全边界

真实凭证只通过本地环境变量或被 Git 忽略的配置注入。提交前请复制示例文件并按需填写：

```bash
cp .env.example .env.local
```

不要提交以下内容：

- `deploy/.env`、`backend/.env.workpc` 与任何真实 API Key；
- `.pi/`、`.cloud/`、`.codex/`、`.agents/`、`.claude/` 等本地工具状态；
- Python 缓存、Maven `target/`、前端 `dist/`、测试报告和临时导出物；
- 个人账号、Cookie、浏览器 Profile、私密截图或本机路径配置。

仓库中的 `.env.example` 只包含占位符和配置说明。

## 质量检查

```bash
cd frontend
npm run build
```

本轮视觉验收覆盖 1440×900 桌面布局与 390×844 移动布局，重点检查登录页、导航折叠、表单高度、可读性与横向溢出。Docker 前端容器的 `/login` 和 `manifest.webmanifest` 已完成 HTTP 健康检查。

## 设计原则

- 让用户记住内容，而不是记住 UI 特效。
- 让原始画作成为视觉主角，控件只负责引导。
- 用语义化色彩、间距、圆角和阴影建立层级，不用无差别发光制造层级。
- 桌面端强调展厅叙事，移动端优先保证阅读、登录和触控目标。
- 保留本地字体组合 ComicShannsMono + 华文行楷，让品牌气质来自字形和内容，而不是动态渐变。

## 文档索引

- [产品设计文档](./Mnemoscape-Design-Document.md)
- [前端优化记录](./docs/frontend-optimization-2026-09-06.md)
- [ReAct 团队协作开发提示词](./docs/全栈开发提示词-ReAct团队协作版.md)
- [前端启动说明](./frontend/README.md)
- [资源说明](./resource/README.md)

## 说明

这是一个持续演进中的个人项目。当前提交聚焦于前端视觉系统、Docker 可运行性、素材组织和项目文档；后端服务与数据基础设施保持原有职责边界。
