# Mnemoscape — 用 AI 重构你的记忆宇宙

## 🧠 个人记忆博物馆 · 创新设计方案 v2.0

---

## 一、核心概念

### 1.1 一句话定义

> **Mnemoscape** 是一个 AI 驱动的个人记忆博物馆——你用自然语言描述一段记忆，AI 将其重构为一座**可穿越、可漫游、可重写的沉浸式 3D 记忆空间**。

### 1.2 情感内核

每个人的一生都是一座独一无二的博物馆，但记忆会褪色、模糊、扭曲。Mnemoscape 做的事情是：

```
褪色的记忆 ──► 自然语言描述 ──► AI 感官重建 ──► 可漫游的 3D 空间
                                                    │
                                          ┌─────────┴─────────┐
                                          │                   │
                                    你可以「走进去」      别人可以「拜访」
                                    重新感受那一刻       你的人生博物馆
```

### 1.3 与上一版的核心差异

| 维度 | v1.0 ChronoVerse（泛化） | v2.0 Mnemoscape（聚焦） |
|-----|------------------------|----------------------|
| **主题** | 任意时空叙事 | **个人记忆与情感** |
| **输入** | 虚构的时间事件 | **真实/半真实的个人经历** |
| **AI 角色** | 时间编织者 | **记忆修复师** |
| **空间形态** | 抽象时空连续体 | **具象的记忆场景** |
| **社交** | 时间线穿越 | **记忆拜访与共鸣** |
| **情感** | 科幻感、探索感 | **怀旧、治愈、共鸣** |
| **悖论机制** | 时间悖论 | **记忆偏差与重构** |

---

## 二、四大核心创意机制

### 2.1 机制 A：记忆重建 (Memory Reconstruction)

用户用自然语言描述一段记忆，AI 将其分解为**五感维度**并重建为 3D 空间：

```
用户输入:
"2019年夏天，我和爷爷在老家院子里乘凉。
 院子里有一棵很大的桂花树，蝉鸣声很响，
  爷爷在摇蒲扇，空气里有蚊香的味道..."

AI 解构为五感维度:
┌──────────────────────────────────────────────────┐
│  👁️ 视觉: 老旧院落 / 桂花树冠 / 暖黄色灯光       │
│  👂 听觉: 蝉鸣(主频4kHz) / 蒲扇摇动声 / 远处犬吠  │
│  👃 嗅觉: 桂花香 / 蚊香 / 夏夜泥土气息             │
│  ✋ 触觉: 凉席的触感 / 夏夜微风 / 蒲扇的风         │
│  💛 情绪: 温暖 / 安宁 / 一丝隐约的忧伤             │
└──────────────────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │   AI 生成 3D 记忆空间   │
        │                       │
        │  Three.js + WebGL     │
        │  实时渲染的院落场景     │
        │  动态光影 + 粒子效果    │
        │  空间音频定位           │
        └───────────────────────┘
```

**关键创新**：AI 不是凭空创造，而是基于心理学中的「情景记忆」(Episodic Memory) 理论，将模糊的文字记忆还原为多感官的沉浸体验。

### 2.2 机制 B：记忆漫游 (Memory Wandering)

重建后的记忆空间不是静态的——用户可以**走进去**自由探索：

```
┌─────────────────────────────────────────────────────────┐
│                    记忆漫游模式                            │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  WASD 移动    鼠环顾四周    E 互动    Tab 记忆地图        │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │                                                 │    │
│  │   [3D 院落场景]                                  │    │
│  │                                                 │    │
│  │        🌳 桂花树                                 │    │
│  │       ╱    ╲                                    │    │
│  │      🪑      🪑   ← 爷爷坐在这里                │    │
│  │     ╱  你在这里  ╲                               │    │
│  │    🏠              🚪                           │    │
│  │                                                 │    │
│  │   💡 发现: 靠近桂花树时，桂花香浓度增加           │    │
│  │   💡 发现: 走到爷爷身边，能听到蒲扇声            │    │
│  │   💡 发现: 抬头看天，星星在缓慢移动              │    │
│  │                                                 │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  📝 记忆注解: "走近桂花树时，我想起爷爷说               │
│     '这棵树是你出生那年种的'..."                         │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

**隐藏发现系统**：每个记忆空间中散布着「记忆碎片」，用户探索时可以触发：
- 被遗忘的细节（AI 根据上下文推理生成）
- 情绪闪回（突然的情绪波动 + 视觉滤镜变化）
- 关联记忆（通往另一段记忆的「门」）

### 2.3 机制 C：记忆偏差引擎 (Memory Drift Engine)

这是整个平台最具创新性的机制——**记忆不是精确的，它会随时间漂移**：

```
核心原理 (基于认知心理学):
┌──────────────────────────────────────────────────────┐
│                                                       │
│   真实事件 ──► 编码 ──► 存储 ──► 提取 ──► 重建       │
│                          │              │             │
│                          ▼              ▼             │
│                     遗忘曲线         记忆偏差          │
│                     (Ebbinghaus)    (Loftus)          │
│                          │              │             │
│                          └──────┬───────┘             │
│                                 ▼                     │
│                         Mnemoscape 模拟               │
│                                                       │
│  时间越久远的记忆:                                      │
│  - 颜色逐渐褪去 (饱和度降低)                            │
│  - 声音变得模糊 (混响增加)                              │
│  - 空间出现「空白区域」(雾气覆盖)                       │
│  - 细节可能被 AI 「合理化填补」(标注为推断)             │
│                                                       │
└──────────────────────────────────────────────────────┘
```

**实际效果**：

| 记忆年龄 | 视觉效果 | 交互提示 |
|---------|---------|---------|
| 1 年内 | 清晰、饱和度高 | 「这段记忆还很鲜活」 |
| 3-5 年 | 轻微褪色、边缘柔和 | 「记忆开始模糊了...」 |
| 10 年+ | 明显褪色、部分区域被雾覆盖 | 「有些细节已经想不起来了」 |
| 20 年+ | 接近黑白、大面积空白 | AI 提示「要我帮你填补这些空白吗？」 |

**用户可以选择**：
- 🔒 **锁定记忆**：保持当前状态，不再漂移
- ✏️ **重写记忆**：用新的描述修正偏差
- 🌀 **拥抱漂移**：让 AI 自由演绎「记忆可能变成的样子」

### 2.4 机制 D：记忆共鸣 (Memory Resonance)

社交层——不是简单的「分享」，而是**深度的情感连接**：

```
┌──────────────────────────────────────────────────────────┐
│                    记忆共鸣机制                            │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  你的记忆              好友的记忆                          │
│  "夏夜和爷爷乘凉"      "小时候在外婆家看星星"              │
│       │                       │                           │
│       └─────── AI 匹配 ───────┘                           │
│               │                                           │
│               ▼                                           │
│       共鸣点: "祖辈陪伴的夏夜"                              │
│               │                                           │
│               ▼                                           │
│  ┌─────────────────────────────────────────────┐         │
│  │         共鸣空间 (Resonance Space)            │         │
│  │                                              │         │
│  │   两个记忆空间融合为一个混合场景:              │         │
│  │   - 左侧: 你的院落 + 桂花树                  │         │
│  │   - 右侧: 好友的屋顶 + 星空                  │         │
│  │   - 中间: 融合区域，两个场景的元素交织         │         │
│  │                                              │         │
│  │   两位用户以「记忆幽灵」形式出现在对方空间     │         │
│  │   可以看到彼此，但不能直接对话                  │         │
│  │   只能通过「留下记忆便签」交流                  │         │
│  └─────────────────────────────────────────────┘         │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

**共鸣匹配算法**：
```typescript
interface ResonanceMatch {
  // 基于情绪相似度
  emotionSimilarity: number;    // 情绪向量余弦相似度
  // 基于场景语义
  sceneSemanticScore: number;   // 场景描述的语义距离
  // 基于时间跨度
  temporalProximity: number;    // 记忆年代的接近程度
  // 基于感官重叠
  sensoryOverlap: number;       // 五感描述的重叠度
  
  // 综合共鸣强度
  resonanceScore: number;       // 加权综合分数
}
```

---

## 三、交互范式：不是编辑器，是「博物馆」

### 3.1 界面隐喻

整个平台的 UI 不是传统编辑器，而是一座**博物馆的导览界面**：

```
┌─────────────────────────────────────────────────────────────────────┐
│  🏛️ Mnemoscape — 我的记忆博物馆                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                                                              │  │
│   │              [3D 记忆空间全景]                                │  │
│   │                                                              │  │
│   │         🌳          🌙                                        │  │
│   │        ╱  ╲                                                   │  │
│   │       🪑    🪑     ← 记忆中的场景实时渲染                      │  │
│   │      ╱ 你  ╲                                                 │  │
│   │     🏠        🚪                                              │  │
│   │                                                              │  │
│   │   ┌──────────────────────────────────────────┐               │  │
│   │   │ 🎵 蝉鸣 ... 蒲扇声 ... 远处犬吠          │  ← 空间音频   │  │
│   │   └──────────────────────────────────────────┘               │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│   │ 📖 记忆   │  │ 🗺️ 记忆   │  │ 💭 记忆   │  │ 🤝 记忆   │          │
│   │    手账   │  │    地图   │  │    偏差   │  │    共鸣   │          │
│   │          │  │          │  │          │  │          │          │
│   │ 2019夏   │  │ ●──●     │  │ 褪色:30% │  │ 3个共鸣  │          │
│   │ 2020秋   │  │  ╱ ╲     │  │ 模糊:15% │  │ 待探索   │          │
│   │ 2021春   │  │ ●   ●    │  │ 推断:2处 │  │          │          │
│   └──────────┘  └──────────┘  └──────────┘  └──────────┘          │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  🧠 AI 记忆修复师                                             │  │
│   │                                                              │  │
│   │  "这段记忆已经过去了6年，有些细节开始模糊了。                   │  │
│   │   我注意到你提到了'桂花树'，但院子的布局可能                   │  │
│   │   不完全准确——要我基于那个年代南方院落的                       │  │
│   │   典型样式帮你补全吗？"                                       │  │
│   │                                                              │  │
│   │  [补全细节]  [保持原样]  [让我自己描述]                        │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 五种交互模式

| 模式 | 隐喻 | 操作 |
|-----|------|------|
| **建馆模式** | 布置展厅 | 自然语言描述记忆 → AI 生成 3D 空间 |
| **漫游模式** | 走进展厅 | WASD + 鼠标，自由探索记忆空间 |
| **修复模式** | 修复展品 | AI 检测偏差 → 用户确认/修正/重写 |
| **拜访模式** | 参观他人博物馆 | 进入好友的记忆空间，以幽灵形式漫游 |
| **共鸣模式** | 联合展览 | 两个相似记忆融合为共鸣空间 |

---

## 四、技术架构：前后端分离

### 4.1 整体架构

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Mnemoscape Platform                              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                        Frontend Layer                              │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐  │  │
│  │  │ Memory   │ │ Memory   │ │ Drift    │ │ Resonance│ │ Museum │  │  │
│  │  │ Builder  │ │ Explorer │ │ Visualizer│ │ Hub     │ │ Map    │  │  │
│  │  │(建馆界面)│ │(漫游引擎)│ │(偏差可视化)│ │(共鸣空间)│ │(记忆地图)│ │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └────────┘  │  │
│  │                                                                     │  │
│  │  React 19 + Three.js + React Three Fiber + Zustand + Howler.js    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                    │                                      │
│                                    ▼                                      │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                   Real-Time Gateway (WebSocket)                     │  │
│  │                    Socket.io + Redis Pub/Sub                        │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                    │                                      │
│         ┌──────────────────────────┼──────────────────────────┐          │
│         │                          │                          │          │
│         ▼                          ▼                          ▼          │
│  ┌──────────────┐          ┌──────────────┐          ┌──────────────┐   │
│  │   Memory     │          │   Sensory    │          │   Resonance  │   │
│  │   Engine     │          │   AI Core    │          │   Engine     │   │
│  │  (记忆逻辑)   │          │  (五感生成)   │          │  (共鸣匹配)  │   │
│  │              │          │              │          │              │   │
│  │  - 记忆CRUD  │          │  - LLM 路由   │          │  - 情绪匹配  │   │
│  │  - 偏差计算  │          │  - 五感解析   │          │  - 场景融合  │   │
│  │  - 褪色模拟  │          │  - 3D 场景生成│          │  - 幽灵同步  │   │
│  │  - 版本管理  │          │  - 音频合成   │          │  - 便签系统  │   │
│  └──────────────┘          └──────────────┘          └──────────────┘   │
│         │                          │                          │          │
│         └──────────────────────────┼──────────────────────────┘          │
│                                    │                                      │
│                                    ▼                                      │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                         Data Layer                                  │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │  │
│  │  │ PostgreSQL │ │   Redis    │ │  S3/MinIO  │ │  pgvector  │      │  │
│  │  │ (记忆数据)  │ │ (会话/缓存)│ │ (3D/音频)  │ │ (共鸣检索)  │      │  │
│  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### 4.2 核心服务详解

#### A. Memory Engine (记忆引擎)

```typescript
// 记忆核心数据结构
interface Memory {
  id: string;
  owner: User;
  
  // 记忆元数据
  title: string;
  description: string;
  era: {
    year: number;
    season: 'spring' | 'summer' | 'autumn' | 'winter';
    timeOfDay: 'dawn' | 'morning' | 'afternoon' | 'dusk' | 'night';
    location: string;
    people: string[];
  };
  
  // 五感数据
  sensory: {
    visual: SensoryVisual;     // 场景描述 + 3D 场景数据
    audio: SensoryAudio;       // 环境音 + 空间音频配置
    olfactory: SensoryOlfactory; // 气味描述 (用于 UI 提示)
    tactile: SensoryTactile;   // 触觉描述 (用于 VR)
    emotion: EmotionProfile;   // 情绪向量
  };
  
  // 记忆状态
  drift: {
    age: number;               // 记忆年龄 (天)
    fadeLevel: number;          // 褪色程度 0-1
    blurAreas: Area[];         // 模糊区域
    inferredDetails: InferredDetail[]; // AI 推断的细节
    isLocked: boolean;         // 是否锁定
  };
  
  // 关联
  connections: {
    linkedMemories: string[];  // 关联的其他记忆
    resonancePartners: string[]; // 共鸣伙伴
  };
  
  // 版本历史
  versions: MemoryVersion[];
  
  privacy: 'private' | 'friends' | 'public';
  createdAt: Date;
  modifiedAt: Date;
}

// 记忆偏差计算
class MemoryDriftEngine {
  calculateDrift(memory: Memory): DriftState {
    const ageInDays = daysBetween(memory.createdAt, new Date());
    
    // 基于 Ebbinghaus 遗忘曲线
    const retention = Math.exp(-ageInDays / 100); // 简化模型
    
    return {
      fadeLevel: 1 - retention,
      blurAreas: this.generateBlurAreas(memory, retention),
      colorDesaturation: (1 - retention) * 0.7,
      audioReverb: (1 - retention) * 0.5,
      inferredDetails: this.findGaps(memory, retention)
    };
  }
  
  // 模拟记忆褪色的视觉效果
  applyDriftVisuals(scene: THREE.Scene, drift: DriftState): void {
    // 降低饱和度
    scene.fog = new THREE.FogExp2(0x000000, drift.fadeLevel * 0.02);
    
    // 模糊区域用半透明雾气覆盖
    drift.blurAreas.forEach(area => {
      const fogGeometry = new THREE.SphereGeometry(area.radius, 32, 32);
      const fogMaterial = new THREE.MeshBasicMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: drift.fadeLevel * 0.6
      });
      const fogMesh = new THREE.Mesh(fogGeometry, fogMaterial);
      fogMesh.position.set(area.x, area.y, area.z);
      scene.add(fogMesh);
    });
  }
}
```

#### B. Sensory AI Core (五感 AI 核心)

```typescript
class SensoryAICore {
  // 从自然语言重建五感体验
  async reconstructMemory(description: string): Promise<SensoryProfile> {
    const [visual, audio, emotion] = await Promise.all([
      // 1. 视觉重建 → 生成 3D 场景
      this.reconstructVisual(description),
      // 2. 听觉重建 → 生成空间音频
      this.reconstructAudio(description),
      // 3. 情绪分析 → 生成情绪向量
      this.analyzeEmotion(description)
    ]);
    
    return { visual, audio, emotion };
  }
  
  private async reconstructVisual(description: string): Promise<SceneData> {
    // Step 1: LLM 提取场景元素
    const sceneElements = await this.llm.extractSceneElements(description);
    // → { environment: "院落", objects: ["桂花树", "藤椅", "蒲扇"], 
    //     lighting: "暖黄", atmosphere: "夏夜" }
    
    // Step 2: 生成 3D 资产提示
    const assetPrompts = await this.generateAssetPrompts(sceneElements);
    
    // Step 3: 调用 3D 生成 API (Meshy/Tripo3D)
    const assets3D = await this.generate3DAssets(assetPrompts);
    
    // Step 4: 组装 Three.js 场景
    const sceneData = this.assembleScene(sceneElements, assets3D);
    
    return sceneData;
  }
  
  private async reconstructAudio(description: string): Promise<AudioData> {
    // Step 1: LLM 提取声音元素
    const soundElements = await this.llm.extractSoundElements(description);
    // → [{ type: "ambient", name: "蝉鸣", position: "above", volume: 0.7 },
    //     { type: "object", name: "蒲扇", position: "nearby", volume: 0.3 }]
    
    // Step 2: 匹配/生成音频资产
    const audioAssets = await this.matchOrGenerateAudio(soundElements);
    
    // Step 3: 配置空间音频参数
    return this.configureSpatialAudio(soundElements, audioAssets);
  }
}
```

#### C. Resonance Engine (共鸣引擎)

```typescript
class ResonanceEngine {
  // 查找与目标记忆共鸣的其他记忆
  async findResonances(memory: Memory): Promise<ResonanceMatch[]> {
    // 1. 情绪向量匹配 (pgvector)
    const emotionMatches = await this.vectorSearch(
      memory.sensory.emotion.vector,
      'emotion_embeddings',
      { threshold: 0.75 }
    );
    
    // 2. 场景语义匹配
    const sceneMatches = await this.semanticSearch(
      memory.description,
      'memory_descriptions',
      { threshold: 0.7 }
    );
    
    // 3. 综合评分
    return this.rankResonances(emotionMatches, sceneMatches);
  }
  
  // 生成共鸣空间 (两个记忆的融合场景)
  async createResonanceSpace(
    memoryA: Memory, 
    memoryB: Memory
  ): Promise<ResonanceSpace> {
    // 1. 找到两个场景的共鸣元素
    const commonElements = this.findCommonElements(memoryA, memoryB);
    
    // 2. 生成融合场景
    const mergedScene = await this.mergeScenes(
      memoryA.sensory.visual.sceneData,
      memoryB.sensory.visual.sceneData,
      commonElements
    );
    
    // 3. 混合音频环境
    const blendedAudio = this.blendAudioEnvironments(
      memoryA.sensory.audio,
      memoryB.sensory.audio
    );
    
    return { scene: mergedScene, audio: blendedAudio, commonElements };
  }
}
```

---

## 五、数据模型

```sql
-- 记忆 (Memories)
CREATE TABLE memories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID REFERENCES users(id),
  title VARCHAR(255) NOT NULL,
  description TEXT NOT NULL,
  
  -- 时代信息
  memory_year INTEGER,
  memory_season VARCHAR(10),
  memory_time_of_day VARCHAR(10),
  memory_location VARCHAR(255),
  memory_people TEXT[],
  
  -- 五感数据
  visual_data JSONB,          -- Three.js 场景序列化
  audio_data JSONB,            -- 空间音频配置
  olfactory_data JSONB,        -- 气味描述
  tactile_data JSONB,          -- 触觉描述
  emotion_profile JSONB,       -- 情绪向量 {joy: 0.3, sadness: 0.7, ...}
  
  -- 记忆偏差状态
  drift_fade_level FLOAT DEFAULT 0,
  drift_blur_areas JSONB DEFAULT '[]',
  drift_inferred JSONB DEFAULT '[]',
  is_locked BOOLEAN DEFAULT false,
  
  -- 关联
  linked_memory_ids UUID[],
  
  -- 隐私
  privacy VARCHAR(20) DEFAULT 'private',
  
  created_at TIMESTAMP DEFAULT NOW(),
  modified_at TIMESTAMP DEFAULT NOW()
);

-- 记忆版本 (用于追踪修改历史)
CREATE TABLE memory_versions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  memory_id UUID REFERENCES memories(id),
  version_number INTEGER,
  change_description TEXT,
  snapshot JSONB,               -- 完整的记忆快照
  change_type VARCHAR(20),      -- 'create', 'modify', 'drift', 'lock'
  created_at TIMESTAMP DEFAULT NOW()
);

-- 记忆碎片 (隐藏在空间中的可发现内容)
CREATE TABLE memory_fragments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  memory_id UUID REFERENCES memories(id),
  fragment_type VARCHAR(20),    -- 'forgotten_detail', 'emotion_flashback', 'linked_door'
  content TEXT,
  position_3d JSONB,            -- {x, y, z} 在 3D 空间中的位置
  trigger_condition JSONB,      -- 触发条件 (距离/朝向/时间)
  is_discovered BOOLEAN DEFAULT false,
  created_at TIMESTAMP DEFAULT NOW()
);

-- 共鸣记录 (记忆之间的连接)
CREATE TABLE resonances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  memory_a_id UUID REFERENCES memories(id),
  memory_b_id UUID REFERENCES memories(id),
  resonance_score FLOAT,
  common_elements JSONB,
  merged_scene_data JSONB,
  status VARCHAR(20) DEFAULT 'pending', -- 'pending', 'accepted', 'active'
  created_at TIMESTAMP DEFAULT NOW()
);

-- 记忆便签 (共鸣空间中的交流方式)
CREATE TABLE memory_notes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  author_id UUID REFERENCES users(id),
  resonance_id UUID REFERENCES resonances(id),
  position_3d JSONB,
  content TEXT,
  mood VARCHAR(50),
  created_at TIMESTAMP DEFAULT NOW()
);

-- 向量嵌入 (用于共鸣匹配)
CREATE TABLE memory_embeddings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  memory_id UUID REFERENCES memories(id),
  embedding_type VARCHAR(20),   -- 'emotion', 'scene', 'sensory'
  embedding vector(1536),
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_emotion_embeddings ON memory_embeddings 
  USING ivfflat (embedding vector_cosine_ops)
  WHERE embedding_type = 'emotion';

CREATE INDEX idx_scene_embeddings ON memory_embeddings 
  USING ivfflat (embedding vector_cosine_ops)
  WHERE embedding_type = 'scene';
```

---

## 六、API 设计

### 6.1 RESTful API

```
# 记忆管理
POST   /api/v1/memories                      # 创建新记忆
GET    /api/v1/memories                      # 获取我的记忆列表
GET    /api/v1/memories/:id                  # 获取记忆详情
PUT    /api/v1/memories/:id                  # 更新记忆
DELETE /api/v1/memories/:id                  # 删除记忆
POST   /api/v1/memories/:id/lock             # 锁定/解锁记忆

# AI 重建
POST   /api/v1/reconstruct                   # 从描述重建记忆空间
POST   /api/v1/reconstruct/enhance           # 增强感官细节
POST   /api/v1/reconstruct/fill-gaps         # 填补记忆空白

# 记忆偏差
GET    /api/v1/memories/:id/drift            # 获取当前偏差状态
POST   /api/v1/memories/:id/drift/simulate   # 模拟未来偏差
POST   /api/v1/memories/:id/drift/restore    # 恢复到某个版本

# 记忆碎片
GET    /api/v1/memories/:id/fragments        # 获取碎片列表
POST   /api/v1/fragments/:id/discover        # 标记发现碎片

# 共鸣
GET    /api/v1/resonances                    # 获取推荐共鸣
POST   /api/v1/resonances/:id/accept         # 接受共鸣
POST   /api/v1/resonances/:id/enter          # 进入共鸣空间
POST   /api/v1/resonances/:id/notes          # 留下记忆便签

# 记忆地图
GET    /api/v1/memories/map                  # 获取记忆地图数据
```

### 6.2 WebSocket 事件

```typescript
interface MnemoscapeEvents {
  // 记忆漫游
  'memory:explore': {
    memoryId: string;
    position: { x: number; y: number; z: number };
    lookingAt: { x: number; y: number; z: number };
  };
  
  // 碎片发现
  'fragment:discovered': {
    fragmentId: string;
    content: string;
    type: 'forgotten_detail' | 'emotion_flashback' | 'linked_door';
  };
  
  // 偏差变化
  'drift:changed': {
    memoryId: string;
    newFadeLevel: number;
    newBlurAreas: Area[];
  };
  
  // 共鸣空间
  'resonance:joined': {
    resonanceId: string;
    partner: User;
    ghostPosition: { x: number; y: number; z: number };
  };
  
  // 便签
  'note:placed': {
    noteId: string;
    position: { x: number; y: number; z: number };
    content: string;
    author: User;
  };
}
```

---

## 七、创新功能亮点

### 7.1 🌫️ 记忆褪色效果

基于真实的遗忘曲线模型，记忆空间会随时间自然变化：
- 视觉：颜色饱和度逐渐降低，边缘变得柔和
- 听觉：环境音混响增加，细节声音消失
- 空间：部分区域被「记忆迷雾」覆盖
- 情绪：情绪强度逐渐减弱

### 7.2 🔍 隐藏碎片系统

每个记忆空间中散布着可发现的「碎片」：
- **遗忘的细节**：AI 推理出的可能被遗忘的细节
- **情绪闪回**：突然的情绪波动 + 视觉滤镜
- **记忆之门**：通往关联记忆的入口

### 7.3 🤝 非语言交流

共鸣空间中的交流方式不是打字，而是：
- **记忆便签**：在 3D 空间中留下漂浮的便签
- **情绪共振**：你的情绪变化会影响对方的环境
- **视角共享**：短暂看到对方眼中的世界

### 7.4 📖 自动记忆编年史

系统自动将所有记忆整理为一本可阅读的「人生编年史」：
- 按时间线排列
- 自动生成章节标题
- 标注情绪起伏曲线
- 可导出为 PDF / 电子书

---

## 八、部署架构

```
┌──────────────────────────────────────────────────────────────────┐
│                         CDN / Cloudflare                          │
└─────────────────────────────┬────────────────────────────────────┘
                              │
                ┌─────────────┴─────────────┐
                │                           │
                ▼                           ▼
┌──────────────────────┐      ┌──────────────────────┐
│  Next.js (Builder)   │      │  Next.js (Explorer)  │
│  - 记忆创建界面       │      │  - 3D 漫游引擎       │
│  - AI 对话界面        │      │  - 共鸣空间入口       │
└──────────┬───────────┘      └──────────┬───────────┘
           │                              │
           └──────────────┬───────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Kubernetes Cluster                           │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │  Memory    │ │  Sensory   │ │  Resonance │ │  WebSocket │    │
│  │  Engine    │ │  AI Core   │ │  Engine    │ │  Gateway   │    │
│  │  (x3 pods) │ │  (x3 pods) │ │  (x2 pods) │ │  (x3 pods) │    │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘    │
└──────────────────────────────┬───────────────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     ▼                     ▼
┌────────────────┐   ┌────────────────┐   ┌────────────────┐
│   PostgreSQL   │   │     Redis      │   │   S3/MinIO     │
│   + pgvector   │   │   Cluster      │   │ (3D/音频资产)  │
└────────────────┘   └────────────────┘   └────────────────┘
```

---

## 九、技术路线图

### Phase 1: 记忆重建 (Q3 2025)
- [ ] 自然语言 → 五感解析
- [ ] Three.js 基础场景生成
- [ ] 空间音频系统
- [ ] 单记忆漫游

### Phase 2: 记忆偏差 (Q4 2025)
- [ ] Ebbinghaus 遗忘曲线模拟
- [ ] 褪色/模糊视觉效果
- [ ] AI 记忆修复建议
- [ ] 版本历史管理

### Phase 3: 记忆共鸣 (Q1 2026)
- [ ] 情绪向量匹配
- [ ] 共鸣空间生成
- [ ] 记忆幽灵系统
- [ ] 记忆便签交流

### Phase 4: 记忆宇宙 (Q2 2026)
- [ ] VR 沉浸模式
- [ ] 记忆编年史自动生成
- [ ] 记忆地图可视化
- [ ] 家庭/群体共享博物馆

---

## 十、灵感来源

1. **TimeThread: 时光的织线** (2025) — 体感交互 + 时间痕迹可视化
2. **teamLab** (2024) — 时空连续体艺术装置
3. **AI.R Taletorium** (2025) — AI 协作叙事 + 角色共情
4. **PlayWrite** (2026, CHI) — XR 空间叙事 + 意图框架
5. **Ebbinghaus 遗忘曲线** — 记忆衰减科学模型
6. **Loftus 记忆重构理论** — 记忆偏差与虚假记忆研究
7. **Proust 效应** — 气味触发 involuntary memory

---

*Mnemoscape — 源自希腊记忆女神 Mnemosyne。在这里，每一段记忆都是一座永不关闭的展厅。*

*版本: v2.0 | 最后更新: 2025-05-19*
