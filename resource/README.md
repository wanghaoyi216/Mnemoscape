# 静态资源池（asset-service WatchService 热加载）

把你想注入到平台的多模态素材丢进对应子目录，**无需重启后端**：
`asset-service` 的 `LocalResourceWatcher` 通过 Java NIO `WatchService` 监听这里
的 `ENTRY_CREATE / MODIFY / DELETE` 事件，命中即重扫并刷新缓存，前端在下一次
`/api/v1/assets/static/resources` 请求时就能拿到新文件（一般在 1 ~ 2 秒内）。

## 目录约定

| 子目录      | 用途                                  | 推荐扩展名                |
|------------|---------------------------------------|---------------------------|
| `photo/`   | 记忆封面 / 记忆卡片插图               | `.png .jpg .jpeg .webp`   |
| `video/`   | 登录页背景 / 沉浸场景 / 记忆 vlog     | `.mp4 .webm .mov`         |
| `audio/`   | 白噪音 / 环境音效 / 声音记忆          | `.mp3 .wav .ogg .flac`    |
| `music/`   | 背景音乐（自动归入 audio 类型）       | `.mp3 .ogg`               |
| `gif/`     | 短动效 / 闪烁记忆碎片                 | `.gif`                    |
| `icon/`    | 自定义 SVG 图标                        | `.svg`                    |

## 使用场景一览

| 业务页面                 | 调用方式                                                        |
|--------------------------|-----------------------------------------------------------------|
| `LoginView`              | 自动循环本目录 `video/*` 作为登录背景，18s 一换                |
| `MemoryListView`         | `pickPhoto(memoryId)` 按记忆 id 稳定哈希选图，避免视图抖动     |
| `MemoryBuilderView`      | "封面艺术"画廊把 `photo/` `gif/` 全部并入，徽章区分 MinIO/本地 |
| `MemoryAtlasView`        | 详情卡按记忆 id/标题/地点关键字匹配 MinIO 图音视               |
| `AiMascotDock`           | `pickVideo('ai-mascot-ambient')` 作为半透明氛围背景           |

## 竞赛资源利用策略

MinIO 不只是"网盘"，它在 Mnemoscape 中承担多模态记忆证据层：

1. `photo/` 与 MinIO 图片作为记忆卡片、详情页、地图节点详情的视觉证据。
2. `video/` 作为登录、地图、AI 重建等待态、场景漫游的动态氛围层。
3. `audio/` 与 `music/` 作为记忆详情、SceneViewer、空间共鸣的环境声轨。
4. 文件名带有记忆 id、标题、地点关键字时，`MemoryAtlasView` 会优先关联到对应记忆节点。
5. 大批量竞赛素材建议上传到 MinIO，轻量兜底素材放本目录，二者通过 `/api/v1/assets/static/resources` 合并返回。

新增外部素材请同步更新 `ASSET_SOURCES.md`，确保评审能追溯来源与授权。

## 体积建议

* 单文件 ≤ 50 MB，超过后 MinIO `presigned URL` 在 1h 后失效时再生成耗时较长
* 一次性投放上百份请走 `POST /api/v1/assets/upload` 接口走 MinIO（更适合大文件）

## MinIO 直传

`asset-service` 同时把 MinIO bucket `mnemoscape-assets` 内的所有对象一起返回到
`/static/resources`。`minio/console` 后台手动上传或通过 `mc cp` 批量同步即可，
前端将自动识别 presigned URL（含 `X-Amz-Signature` 参数）并标注 `MinIO` 徽章。
