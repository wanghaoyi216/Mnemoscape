<script setup lang="ts">
/**
 * Mnemoscape — Enterprise Big Screen Cockpit & Admin Center (大屏系统).
 *
 * Provides a dual-mode operations center:
 *   1. Enterprise Cockpit HUD Mode (数智星穹沉浸大屏) — High-tech 3-column layout with 3D Globe /
 *      3D Constellation / Agent Mesh centerpiece, real-time KPI metrics ticker, auto-patrol tour mode,
 *      multi-theme palette switching, and live ReAct telemetry ticker.
 *   2. Standard Admin Grid Mode (标准运营工作台) — Clean responsive card matrix for operators.
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import ActiveUsersView from './ActiveUsersView.vue'
import MemoryTrendsView from './MemoryTrendsView.vue'
import EmotionDistView from './EmotionDistView.vue'
import HeatmapView from './HeatmapView.vue'
import ContributorsView from './ContributorsView.vue'
import FragmentDiscoveryView from './FragmentDiscoveryView.vue'
import ResonanceOverviewView from './ResonanceOverviewView.vue'
import SystemHealthView from './SystemHealthView.vue'
import MemoryConstellationView from '../MemoryConstellationView.vue'
import AdminDetailDrawer, { type DrawerDetailData } from '../../components/admin/AdminDetailDrawer.vue'

const { t } = useI18n()
const router = useRouter()

// Control States
const isHudActive = ref(true)
const currentTimeString = ref('')
const currentDateString = ref('')
const centerMode = ref<'globe' | 'constellation' | 'topology'>('globe')
const currentTheme = ref<'cockpit' | 'kawaii' | 'aurora' | 'mint'>('cockpit')
const isAutoPatrol = ref(false)
const activePatrolIndex = ref(0)
const isFullscreen = ref(false)

// Inspection Drawer State
const drawerOpen = ref(false)
const selectedDrawerData = ref<DrawerDetailData | null>(null)

let timer = 0
let patrolTimer = 0

// Real-time KPI Metric Cards
const kpiMetrics = ref([
  { id: 'memories', label: '记忆资产总量', value: '4,892', unit: '条', change: '+12.4%', trend: 'up', color: 'cyan' },
  { id: 'synapses', label: '活跃突触节点', value: '1,280', unit: 'nodes', change: '+8.1%', trend: 'up', color: 'purple' },
  { id: 'resonance', label: '情感共鸣指数', value: '96.8', unit: '%', change: '平稳', trend: 'stable', color: 'green' },
  { id: 'agent_tps', label: 'Agent ReAct 吞吐', value: '42.5', unit: 'req/s', change: '高负荷', trend: 'up', color: 'rose' },
  { id: 'p99_latency', label: 'P99 推理时延', value: '185', unit: 'ms', change: '-24ms', trend: 'down', color: 'gold' },
  { id: 'health_score', label: '节点健康度', value: '99.98', unit: '%', change: '6/6 在线', trend: 'stable', color: 'teal' },
])

// Live ReAct Agent Telemetry Logs
interface AgentLog {
  id: string
  time: string
  agent: string
  action: string
  tag: string
  tone: 'cyan' | 'purple' | 'green' | 'rose' | 'gold'
}

const liveAgentLogs = ref<AgentLog[]>([
  { id: '1', time: '10:12:04', agent: 'ChainWorkflowAgent', action: '拆解多步骤记忆重构任务 [时空聚类 + 情感分析]', tag: 'PLAN', tone: 'purple' },
  { id: '2', time: '10:12:05', agent: 'SubAgent_1 (Milvus)', action: '执行向量近邻检索，命中 4 条高相关青岛海边记忆碎片', tag: 'RETRIEVAL', tone: 'cyan' },
  { id: '3', time: '10:12:06', agent: 'SubAgent_2 (Neo4j)', action: '拓扑图谱实体关联，扩展 2 个情感共鸣节点', tag: 'GRAPH', tone: 'gold' },
  { id: '4', time: '10:12:07', agent: 'EnhancedAgent', action: '多模态叙事融合润色，标点分块低时延流式推送', tag: 'SYNTHESIS', tone: 'green' },
  { id: '5', time: '10:12:08', agent: 'AcceptanceAgent', action: '自省校验通过 (Matched: true, Score: 0.985)', tag: 'ACCEPT', tone: 'rose' },
])

function updateTime() {
  const d = new Date()
  currentTimeString.value = d.toLocaleTimeString('zh-CN', { hour12: false })
  currentDateString.value = d.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

function switchTheme(theme: 'cockpit' | 'kawaii' | 'aurora' | 'mint') {
  currentTheme.value = theme
  document.documentElement.className = ''
  document.documentElement.classList.add(`theme-${theme}`)
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen().catch(() => {})
    isFullscreen.value = true
  } else {
    document.exitFullscreen().catch(() => {})
    isFullscreen.value = false
  }
}

function toggleAutoPatrol() {
  isAutoPatrol.value = !isAutoPatrol.value
  if (isAutoPatrol.value) {
    patrolTimer = window.setInterval(() => {
      activePatrolIndex.value = (activePatrolIndex.value + 1) % 6
    }, 6000)
  } else {
    if (patrolTimer) clearInterval(patrolTimer)
  }
}

// Generate dynamic agent logs ticker
function pushRandomAgentLog() {
  const agents = ['ChainWorkflowAgent', 'SubAgent_Milvus', 'SubAgent_Neo4j', 'EnhancedAgent', 'AcceptanceAgent']
  const actions = [
    '执行向量近邻检索，命中 3 条高相关夏夜记忆碎片',
    '动态调度工作流步骤，并行执行 2 个感知子任务',
    '知识图谱实体抽取完成，生成 5 条语义三元组',
    '艾宾浩斯遗忘曲线拟合完成，当前记忆漂移率 4.2%',
    '多模态视觉特征提取完成 (Qwen-VL-Pass, 512 embedding)',
    '自省校验通过 (Matched: true, 意图完备)',
  ]
  const tags: Array<{ tag: string; tone: AgentLog['tone'] }> = [
    { tag: 'PLAN', tone: 'purple' },
    { tag: 'RETRIEVAL', tone: 'cyan' },
    { tag: 'GRAPH', tone: 'gold' },
    { tag: 'SYNTHESIS', tone: 'green' },
    { tag: 'ACCEPT', tone: 'rose' },
  ]
  const pick = Math.floor(Math.random() * tags.length)
  const d = new Date()
  const logItem: AgentLog = {
    id: String(Date.now()),
    time: d.toLocaleTimeString('zh-CN', { hour12: false }),
    agent: agents[Math.floor(Math.random() * agents.length)],
    action: actions[Math.floor(Math.random() * actions.length)],
    tag: tags[pick].tag,
    tone: tags[pick].tone,
  }
  liveAgentLogs.value.unshift(logItem)
  if (liveAgentLogs.value.length > 8) {
    liveAgentLogs.value.pop()
  }
}

function openMetricDrawer(metricId: string) {
  if (metricId === 'memories') {
    selectedDrawerData.value = {
      title: '记忆资产库全景诊断',
      category: 'DATA_WAREHOUSE',
      status: 'HEALTHY',
      value: '4,892',
      unit: '条',
      trend: '+12.4% 本周',
      metrics: [
        { label: '图文多模态占比', value: '78.4%', state: 'good' },
        { label: '平均记忆沉淀长度', value: '428 字' },
        { label: '时空锚点覆盖率', value: '94.2%', state: 'good' },
        { label: '艾宾浩斯漂移速率', value: '0.042/day' },
      ],
      logs: [
        { time: '10:14:02', level: 'INFO', message: '完成 128 条冷记忆向量索引持久化' },
        { time: '10:13:45', level: 'EXEC', message: 'Milvus HNSW 向量索引构建完成 (dim=512)' },
      ],
    }
  } else if (metricId === 'agent_tps') {
    selectedDrawerData.value = {
      title: 'Agent ReAct 推理链路与吞吐',
      category: 'AI_ORCHESTRATION',
      status: 'HEALTHY',
      value: '42.5',
      unit: 'req/s',
      trend: '高峰期稳定',
      agentInfo: {
        name: 'DynamicWorkflowEngine',
        role: '多智能体 DAG 编排中枢',
        tps: 42.5,
        p99: '185ms',
      },
      metrics: [
        { label: '子任务并行度', value: '3-4 并发', state: 'good' },
        { label: '验收通过率 (Acceptance)', value: '98.5%', state: 'good' },
        { label: '自省修正触发率', value: '1.5%' },
        { label: '首 Token 响应时延', value: '110ms', state: 'good' },
      ],
      logs: liveAgentLogs.value.map((l) => ({
        time: l.time,
        level: l.tag === 'ACCEPT' ? 'EXEC' : 'INFO',
        message: `[${l.agent}] ${l.action}`,
      })),
    }
  } else if (metricId === 'synapses') {
    selectedDrawerData.value = {
      title: '知识图谱突触拓扑诊断',
      category: 'GRAPH_DATABASE',
      status: 'HEALTHY',
      value: '1,280',
      unit: 'nodes',
      trend: '+8.1% 新增关联',
      metrics: [
        { label: '实体类型种类', value: '4 类 (人物/地点/物体/情绪)' },
        { label: '三元组关联总数', value: '3,842 条', state: 'good' },
        { label: '图谱聚类系数', value: '0.68' },
        { label: '平均突触度数', value: '4.2' },
      ],
      logs: [
        { time: '10:14:10', level: 'INFO', message: 'Neo4j 知识图谱三元组关系索引优化完成' },
        { time: '10:13:30', level: 'EXEC', message: '共鸣突触图谱聚类计算完成' },
      ],
    }
  } else if (metricId === 'p99_latency') {
    selectedDrawerData.value = {
      title: '分布式链路耗时与 P99 监控',
      category: 'SYSTEM_PERFORMANCE',
      status: 'HEALTHY',
      value: '185',
      unit: 'ms',
      trend: '-24ms 优化',
      metrics: [
        { label: '网关路由平均时延', value: '4.2ms', state: 'good' },
        { label: '向量近邻检索耗时', value: '18ms', state: 'good' },
        { label: '图谱子图查询耗时', value: '22ms', state: 'good' },
        { label: 'LLM 流式首字时延', value: '115ms', state: 'good' },
      ],
      logs: [
        { time: '10:14:05', level: 'INFO', message: '网关长连接池负载稳定，未触发限流' },
      ],
    }
  } else {
    selectedDrawerData.value = {
      title: '微服务集群全节点状态',
      category: 'CLUSTER_HEALTH',
      status: 'HEALTHY',
      value: '99.98',
      unit: '%',
      trend: '6/6 节点健康',
      metrics: [
        { label: 'ai-service', value: 'UP (Reactive 4 cores)', state: 'good' },
        { label: 'memory-service', value: 'UP (MySQL/MyBatis)', state: 'good' },
        { label: 'auth-service', value: 'UP (JWT AuthGuard)', state: 'good' },
        { label: 'resonance-service', value: 'UP (WebSocket Hub)', state: 'good' },
      ],
      logs: [
        { time: '10:14:00', level: 'INFO', message: 'Nacos 服务注册中心心跳探测全绿' },
      ],
    }
  }
  drawerOpen.value = true
}

let agentLogInterval = 0

onMounted(() => {
  updateTime()
  timer = window.setInterval(updateTime, 1000)
  agentLogInterval = window.setInterval(pushRandomAgentLog, 4500)
  switchTheme('cockpit')
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (patrolTimer) clearInterval(patrolTimer)
  if (agentLogInterval) clearInterval(agentLogInterval)
})

interface CellSpec {
  routeName: string
  navKey: string
  span: 'half' | 'full'
}

const cells: CellSpec[] = [
  { routeName: 'AdminActiveUsers',  navKey: 'admin.nav.activeUsers',  span: 'half' },
  { routeName: 'AdminMemoryTrends', navKey: 'admin.nav.memoryTrends', span: 'half' },
  { routeName: 'AdminEmotion',      navKey: 'admin.nav.emotion',      span: 'half' },
  { routeName: 'AdminContributors', navKey: 'admin.nav.contributors', span: 'half' },
  { routeName: 'AdminHeatmap',      navKey: 'admin.nav.heatmap',      span: 'full' },
  { routeName: 'AdminResonance',    navKey: 'admin.nav.resonance',    span: 'full' },
  { routeName: 'AdminFragments',    navKey: 'admin.nav.fragments',    span: 'half' },
  { routeName: 'AdminHealth',       navKey: 'admin.nav.health',       span: 'half' },
]

function componentForCell(routeName: string) {
  switch (routeName) {
    case 'AdminActiveUsers':  return ActiveUsersView
    case 'AdminMemoryTrends': return MemoryTrendsView
    case 'AdminEmotion':      return EmotionDistView
    case 'AdminHeatmap':      return HeatmapView
    case 'AdminContributors': return ContributorsView
    case 'AdminFragments':    return FragmentDiscoveryView
    case 'AdminResonance':    return ResonanceOverviewView
    case 'AdminHealth':       return SystemHealthView
    default: return null
  }
}

function openPanel(name: string): void {
  void router.push({ name })
}
</script>

<template>
  <div class="admin-wrapper" :class="{ 'hud-active': isHudActive }">
    <!-- Header control bar (only shown in normal grid mode) -->
    <div v-if="!isHudActive" class="hud-control-bar">
      <button
        type="button"
        class="hud-toggle-btn hud-toggle-btn--primary"
        @click="isHudActive = true"
      >
        <span class="pulse-dot"></span>
        <span>进入数智星穹大屏 (Cinematic Cockpit)</span>
      </button>
    </div>

    <!-- Regular Grid Mode -->
    <div v-if="!isHudActive" class="admin-grid">
      <article
        v-for="cell in cells"
        :key="cell.routeName"
        class="admin-grid__cell"
        :class="{
          'admin-grid__cell--full': cell.span === 'full',
        }"
      >
        <header class="admin-grid__cell-head">
          <button
            type="button"
            class="admin-grid__open"
            :aria-label="t(cell.navKey)"
            @click="openPanel(cell.routeName)"
          >
            <span>{{ t(cell.navKey) }}</span>
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" aria-hidden="true">
              <path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
        </header>

        <div class="admin-grid__panel-wrap">
          <component :is="componentForCell(cell.routeName)" />
        </div>
      </article>
    </div>

    <!-- Enterprise Sci-Fi HUD Cockpit Mode -->
    <div v-else class="hud-dashboard">
      <!-- Digital scanlines & background grid overlay -->
      <div class="hud-scanlines"></div>
      <div class="hud-grid-overlay"></div>
      <div class="hud-ambient-glow"></div>

      <!-- HUD Top Command Bar -->
      <header class="hud-header">
        <div class="hud-header__corner hud-header__corner--left"></div>
        <div class="hud-header__corner hud-header__corner--right"></div>

        <!-- Left: Logo & Title -->
        <div class="hud-header__brand">
          <div class="hud-logo-icon">
            <span class="hud-logo-ring"></span>
            <span class="hud-logo-dot"></span>
          </div>
          <div class="hud-header__title-group">
            <div class="hud-header__title-row">
              <h1 class="hud-header__title">MNEMOSCAPE · 忆境数智星穹大屏</h1>
              <span class="hud-version-pill">ENTERPRISE v3.2</span>
            </div>
            <p class="hud-header__desc">AI-DRIVEN PERSONAL & COLLECTIVE MEMORY ECOSYSTEM COCKPIT</p>
          </div>
        </div>

        <!-- Center: Centerpiece Mode Switcher & Theme Selector -->
        <div class="hud-center-controls">
          <!-- 3D Centerpiece Switcher -->
          <div class="hud-segmented-group" role="group" aria-label="中心视窗模式">
            <button
              type="button"
              class="hud-seg-btn"
              :class="{ 'hud-seg-btn--active': centerMode === 'globe' }"
              @click="centerMode = 'globe'"
            >
              <span>🌍 3D 情绪热力地球</span>
            </button>
            <button
              type="button"
              class="hud-seg-btn"
              :class="{ 'hud-seg-btn--active': centerMode === 'constellation' }"
              @click="centerMode = 'constellation'"
            >
              <span>✨ 3D 记忆星宿星座</span>
            </button>
            <button
              type="button"
              class="hud-seg-btn"
              :class="{ 'hud-seg-btn--active': centerMode === 'topology' }"
              @click="centerMode = 'topology'"
            >
              <span>🧠 智能体协同拓扑</span>
            </button>
          </div>

          <!-- Theme Selector -->
          <div class="hud-theme-group">
            <button
              type="button"
              class="hud-theme-pill"
              :class="{ 'hud-theme-pill--active': currentTheme === 'cockpit' }"
              title="数智星穹 (Cyberpunk Cockpit)"
              @click="switchTheme('cockpit')"
            >
              <span class="theme-color-dot theme-color-dot--cockpit"></span>
              <span>数智星穹</span>
            </button>
            <button
              type="button"
              class="hud-theme-pill"
              :class="{ 'hud-theme-pill--active': currentTheme === 'kawaii' }"
              title="星野治愈 (Kawaii Peach)"
              @click="switchTheme('kawaii')"
            >
              <span class="theme-color-dot theme-color-dot--kawaii"></span>
              <span>星野治愈</span>
            </button>
            <button
              type="button"
              class="hud-theme-pill"
              :class="{ 'hud-theme-pill--active': currentTheme === 'aurora' }"
              title="极光深海 (Aurora Cyan)"
              @click="switchTheme('aurora')"
            >
              <span class="theme-color-dot theme-color-dot--aurora"></span>
              <span>极光深海</span>
            </button>
            <button
              type="button"
              class="hud-theme-pill"
              :class="{ 'hud-theme-pill--active': currentTheme === 'mint' }"
              title="暮山薄荷 (Mint Tea)"
              @click="switchTheme('mint')"
            >
              <span class="theme-color-dot theme-color-dot--mint"></span>
              <span>暮山薄荷</span>
            </button>
          </div>
        </div>

        <!-- Right: Real-time Telemetry, Patrol & Fullscreen -->
        <div class="hud-header__right">
          <!-- Auto Patrol Button -->
          <button
            type="button"
            class="hud-tool-btn"
            :class="{ 'hud-tool-btn--active': isAutoPatrol }"
            @click="toggleAutoPatrol"
            title="开启/关闭大屏自动巡检轮播"
          >
            <span class="hud-tool-icon">🔄</span>
            <span>{{ isAutoPatrol ? '巡检中 (ON)' : '自动巡检' }}</span>
          </button>

          <!-- Fullscreen Toggle -->
          <button
            type="button"
            class="hud-tool-btn"
            @click="toggleFullscreen"
            title="切换全屏沉浸模式"
          >
            <span class="hud-tool-icon">⛶</span>
            <span>{{ isFullscreen ? '退出全屏' : '全屏展示' }}</span>
          </button>

          <!-- Time & Status -->
          <div class="hud-header__time">
            <div class="hud-header__status-row">
              <span class="hud-header__pulse">● SYSTEM ONLINE</span>
              <span class="hud-header__date">{{ currentDateString }}</span>
            </div>
            <span class="hud-header__clock">{{ currentTimeString }}</span>
          </div>

          <!-- Exit Cockpit -->
          <button
            type="button"
            class="hud-exit-btn"
            @click="isHudActive = false"
            title="返回标准运营面板"
          >
            <span class="pulse-dot pulse-dot--red"></span>
            <span>退出大屏</span>
          </button>
        </div>
      </header>

      <!-- KPI Metrics Ticker Header -->
      <section class="hud-kpi-bar">
        <div
          v-for="kpi in kpiMetrics"
          :key="kpi.id"
          class="hud-kpi-card"
          :class="[`hud-kpi-card--${kpi.color}`]"
          role="button"
          tabindex="0"
          :title="`点击深度诊断 ${kpi.label}`"
          @click="openMetricDrawer(kpi.id)"
          @keydown.enter="openMetricDrawer(kpi.id)"
        >
          <div class="hud-kpi-card__inner">
            <div class="hud-kpi-label-row">
              <span class="hud-kpi-label">{{ kpi.label }}</span>
              <span class="hud-kpi-badge" :class="[`hud-kpi-badge--${kpi.trend}`]">{{ kpi.change }}</span>
            </div>
            <div class="hud-kpi-val-row">
              <span class="hud-kpi-num numeric">{{ kpi.value }}</span>
              <span class="hud-kpi-unit">{{ kpi.unit }}</span>
            </div>
          </div>
          <div class="hud-kpi-glow-line"></div>
        </div>
      </section>

      <!-- Main HUD 3-Column Layout -->
      <div class="hud-layout">
        <!-- Left Column: Active Users, Memory Trends, System Health -->
        <aside class="hud-col hud-col--left">
          <div class="hud-card" :class="{ 'hud-card--focal': isAutoPatrol && activePatrolIndex === 0 }">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.activeUsers') }}</h2>
              <span class="hud-card__badge hud-card__badge--cyan">SYS.INGRESS_FLOW</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ MONITORING REAL-TIME CONCURRENT CHANNELS...</p>
            <div class="hud-card__content">
              <ActiveUsersView />
            </div>
          </div>

          <div class="hud-card" :class="{ 'hud-card--focal': isAutoPatrol && activePatrolIndex === 1 }">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.memoryTrends') }}</h2>
              <span class="hud-card__badge hud-card__badge--purple">MEM.RECON_TREND</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ TRACKING RECONSTRUCTION VELOCITY...</p>
            <div class="hud-card__content">
              <MemoryTrendsView />
            </div>
          </div>

          <div class="hud-card" :class="{ 'hud-card--focal': isAutoPatrol && activePatrolIndex === 2 }">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.health') }}</h2>
              <span class="hud-card__badge hud-card__badge--green">MESH.NODE_MATRIX</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ SCANNING DISTRIBUTED SERVICES LATENCY...</p>
            <div class="hud-card__content">
              <SystemHealthView />
            </div>
          </div>
        </aside>

        <!-- Center Column: 3D Globe / 3D Constellation / Topology + Live Agent ReAct Telemetry -->
        <main class="hud-col hud-col--center">
          <!-- Centerpiece Viewport -->
          <div class="hud-card hud-card--centerpiece">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-globe-scope"></div>

            <div class="hud-card__header-row hud-center-header">
              <div class="hud-center-title-box">
                <h2 class="hud-card__title">
                  {{ centerMode === 'globe' ? '全球情感共鸣热力 · 3D 数字孪生' : centerMode === 'constellation' ? '3D 记忆星宿群 · 空间五芒星阵' : 'AI Agent 协同拓扑与调用链矩阵' }}
                </h2>
                <span class="hud-card__badge hud-card__badge--rose">LIVE.SPATIAL_SYNC</span>
              </div>
              <div class="hud-center-meta">
                <span class="hud-pulse-tag">60 FPS REALTIME</span>
              </div>
            </div>

            <!-- Dynamic Center Component -->
            <div class="hud-card__content hud-card__content--center">
              <HeatmapView v-if="centerMode === 'globe'" />
              <MemoryConstellationView v-else-if="centerMode === 'constellation'" />
              <SystemHealthView v-else />
            </div>
          </div>

          <!-- Bottom Center: Real-Time AI Agent ReAct Telemetry Ticker -->
          <div class="hud-card hud-card--agent-ticker">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <div class="hud-ticker-title">
                <span class="hud-ticker-icon">🤖</span>
                <h3 class="hud-ticker-heading">AI AGENT 实时协同与 REACT 执行流监控</h3>
              </div>
              <span class="hud-card__badge hud-card__badge--cyan">REACTIVE STREAM ENGINE</span>
            </div>
            
            <div class="hud-agent-log-list">
              <transition-group name="log-slide" tag="div" class="hud-agent-log-wrap">
                <div
                  v-for="item in liveAgentLogs"
                  :key="item.id"
                  class="hud-agent-log-item"
                  :class="[`hud-agent-log-item--${item.tone}`]"
                >
                  <span class="hud-log-time">{{ item.time }}</span>
                  <span class="hud-log-tag">{{ item.tag }}</span>
                  <span class="hud-log-agent">[{{ item.agent }}]</span>
                  <span class="hud-log-action">{{ item.action }}</span>
                </div>
              </transition-group>
            </div>
          </div>
        </main>

        <!-- Right Column: Emotion Radar, Contributors, Fragment Discovery -->
        <aside class="hud-col hud-col--right">
          <div class="hud-card" :class="{ 'hud-card--focal': isAutoPatrol && activePatrolIndex === 3 }">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.emotion') }}</h2>
              <span class="hud-card__badge hud-card__badge--cyan">AESTHETICS.MIND_RADAR</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ COMPUTING OCTAGONAL EMOTION VECTORS...</p>
            <div class="hud-card__content">
              <EmotionDistView />
            </div>
          </div>

          <div class="hud-card" :class="{ 'hud-card--focal': isAutoPatrol && activePatrolIndex === 4 }">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.contributors') }}</h2>
              <span class="hud-card__badge hud-card__badge--purple">SYNAPSE.GRAVITY_RANK</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ RANKING MEMORY NODE CONTRIBUTIONS...</p>
            <div class="hud-card__content">
              <ContributorsView />
            </div>
          </div>

          <div class="hud-card" :class="{ 'hud-card--focal': isAutoPatrol && activePatrolIndex === 5 }">
            <div class="hud-card__corner hud-card__corner--tl"></div>
            <div class="hud-card__corner hud-card__corner--br"></div>
            <div class="hud-card__header-row">
              <h2 class="hud-card__title">{{ t('admin.nav.fragments') }}</h2>
              <span class="hud-card__badge hud-card__badge--rose">MND.UNSTABLE_FRAGMENTS</span>
            </div>
            <p class="hud-card__subtitle">&gt;_ SCANNING FOR FORGOTTEN MEMORY NODES...</p>
            <div class="hud-card__content">
              <FragmentDiscoveryView />
            </div>
          </div>
        </aside>
      </div>

      <!-- Enterprise Diagnostics & Telemetry Inspection Drawer -->
      <AdminDetailDrawer
        :open="drawerOpen"
        :data="selectedDrawerData"
        @close="drawerOpen = false"
      />
    </div>
  </div>
</template>

<style scoped>
.admin-wrapper {
  position: relative;
  width: 100%;
}

.hud-control-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 20px;
  z-index: 100;
  position: relative;
}

.hud-toggle-btn {
  appearance: none;
  background: rgba(15, 23, 42, 0.7);
  border: 1px solid var(--border-accent);
  color: var(--primary);
  padding: 10px 22px;
  border-radius: var(--radius-md);
  font-family: inherit;
  font-weight: 700;
  letter-spacing: 0.08em;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  backdrop-filter: blur(12px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
  transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

.hud-toggle-btn:hover {
  background: var(--surface-elevated);
  box-shadow: var(--shadow-glow);
  transform: translateY(-2px);
}

.pulse-dot {
  width: 8px;
  height: 8px;
  background-color: var(--success);
  border-radius: 50%;
  box-shadow: 0 0 10px var(--success);
  animation: pulse 1.5s infinite;
  flex-shrink: 0;
}

.pulse-dot--red {
  background-color: #f43f5e;
  box-shadow: 0 0 10px #f43f5e;
}

@keyframes pulse {
  0% { transform: scale(0.9); opacity: 0.6; }
  50% { transform: scale(1.3); opacity: 1; }
  100% { transform: scale(0.9); opacity: 0.6; }
}

/* Regular Grid */
.admin-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 20px;
}

.admin-grid__cell {
  grid-column: span 6;
  display: flex;
  flex-direction: column;
  gap: 10px;
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 16px;
  box-shadow: var(--shadow-sm);
  backdrop-filter: blur(12px);
}

.admin-grid__cell--full {
  grid-column: span 12;
}

.admin-grid__cell-head {
  display: flex;
  justify-content: flex-end;
}

.admin-grid__open {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.82rem;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  transition: all 160ms ease;
}

.admin-grid__open:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.06);
}

/* ─────────────────────────────────────────────────────────────
   Enterprise Sci-Fi HUD Cockpit Dashboard Styles
   ───────────────────────────────────────────────────────────── */
.hud-dashboard {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: #02050c;
  color: var(--text);
  overflow: hidden;
  padding: 16px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-family: var(--font-sans);
}

.hud-scanlines {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    rgba(18, 16, 16, 0) 50%,
    rgba(0, 0, 0, 0.28) 50%
  );
  background-size: 100% 4px;
  pointer-events: none;
  z-index: 10;
}

.hud-grid-overlay {
  position: absolute;
  inset: 0;
  background-image: 
    linear-gradient(rgba(0, 242, 254, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 242, 254, 0.025) 1px, transparent 1px);
  background-size: 48px 48px;
  pointer-events: none;
  z-index: 5;
}

.hud-ambient-glow {
  position: absolute;
  top: -10%;
  left: 30%;
  width: 40%;
  height: 30%;
  background: radial-gradient(circle, var(--primary-glow) 0%, transparent 70%);
  filter: blur(60px);
  pointer-events: none;
  z-index: 2;
  opacity: 0.4;
}

/* HUD Top Header Bar */
.hud-header {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: rgba(4, 8, 18, 0.82);
  border: 1px solid var(--border-accent);
  border-radius: var(--radius-md);
  backdrop-filter: blur(16px);
  box-shadow: inset 0 0 24px rgba(0, 242, 254, 0.08), 0 8px 28px rgba(0, 0, 0, 0.6);
  z-index: 30;
  gap: 16px;
}

.hud-header__brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hud-logo-icon {
  position: relative;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hud-logo-ring {
  position: absolute;
  inset: 0;
  border: 2px dashed var(--primary);
  border-radius: 50%;
  animation: logoSpin 16s linear infinite;
}

.hud-logo-dot {
  width: 12px;
  height: 12px;
  background: var(--primary);
  border-radius: 50%;
  box-shadow: 0 0 14px var(--primary);
}

@keyframes logoSpin {
  to { transform: rotate(360deg); }
}

.hud-header__title-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hud-header__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hud-header__title {
  font-size: 1.25rem;
  font-weight: 800;
  letter-spacing: 0.06em;
  color: #fff;
  text-shadow: 0 0 12px var(--primary-glow);
  margin: 0;
}

.hud-version-pill {
  font-size: 0.65rem;
  font-weight: 800;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(0, 242, 254, 0.12);
  border: 1px solid var(--primary);
  color: var(--primary);
  letter-spacing: 0.08em;
}

.hud-header__desc {
  font-size: 0.72rem;
  color: var(--text-muted);
  margin: 0;
  letter-spacing: 0.05em;
}

/* Center Controls: Centerpiece Switcher & Themes */
.hud-center-controls {
  display: flex;
  align-items: center;
  gap: 16px;
}

.hud-segmented-group {
  display: inline-flex;
  background: rgba(8, 14, 28, 0.75);
  border: 1px solid rgba(0, 242, 254, 0.25);
  border-radius: var(--radius-full);
  padding: 3px;
  gap: 4px;
}

.hud-seg-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.75rem;
  font-weight: 700;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 180ms ease;
  white-space: nowrap;
}

.hud-seg-btn:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.06);
}

.hud-seg-btn--active {
  background: linear-gradient(135deg, var(--primary), var(--accent)) !important;
  color: #030a16 !important;
  box-shadow: 0 2px 10px var(--primary-glow);
}

.hud-theme-group {
  display: inline-flex;
  background: rgba(8, 14, 28, 0.75);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-full);
  padding: 3px 6px;
  gap: 4px;
}

.hud-theme-pill {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.72rem;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 160ms ease;
}

.hud-theme-pill:hover {
  color: var(--text);
}

.hud-theme-pill--active {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.theme-color-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.theme-color-dot--cockpit { background: #00f2fe; box-shadow: 0 0 6px #00f2fe; }
.theme-color-dot--kawaii { background: #ff758c; box-shadow: 0 0 6px #ff758c; }
.theme-color-dot--aurora { background: #38ef7d; box-shadow: 0 0 6px #38ef7d; }
.theme-color-dot--mint { background: #36d8b4; box-shadow: 0 0 6px #36d8b4; }

/* Right Control Group */
.hud-header__right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hud-tool-btn {
  appearance: none;
  background: rgba(8, 14, 28, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: var(--text-soft);
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  font-weight: 700;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 180ms ease;
  white-space: nowrap;
}

.hud-tool-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: rgba(0, 242, 254, 0.1);
}

.hud-tool-btn--active {
  background: rgba(0, 242, 254, 0.18) !important;
  border-color: var(--primary) !important;
  color: var(--primary) !important;
  box-shadow: 0 0 12px var(--primary-glow);
}

.hud-header__time {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.hud-header__status-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hud-header__pulse {
  font-size: 0.68rem;
  font-weight: 800;
  color: var(--success);
  letter-spacing: 0.06em;
  text-shadow: 0 0 8px var(--success);
}

.hud-header__date {
  font-size: 0.68rem;
  color: var(--text-muted);
}

.hud-header__clock {
  font-family: var(--font-mono);
  font-size: 1.15rem;
  font-weight: bold;
  color: var(--primary);
  text-shadow: 0 0 10px var(--primary-glow);
}

.hud-exit-btn {
  appearance: none;
  background: rgba(244, 63, 94, 0.1);
  border: 1px solid rgba(244, 63, 94, 0.45);
  color: #f43f5e;
  padding: 6px 14px;
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  backdrop-filter: blur(8px);
  transition: all 0.25s ease;
  white-space: nowrap;
}

.hud-exit-btn:hover {
  background: rgba(244, 63, 94, 0.25);
  box-shadow: 0 0 18px rgba(244, 63, 94, 0.4);
}

/* KPI Ticker Section */
.hud-kpi-bar {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  z-index: 25;
}

.hud-kpi-card {
  position: relative;
  background: rgba(4, 10, 22, 0.72);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  backdrop-filter: blur(12px);
  overflow: hidden;
  cursor: pointer;
  user-select: none;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.hud-kpi-card:hover {
  transform: translateY(-3px);
  border-color: var(--border-accent);
  box-shadow: 0 8px 24px -4px rgba(0, 242, 254, 0.25);
}

.hud-kpi-card:active {
  transform: translateY(-1px) scale(0.99);
}

.hud-kpi-card__inner {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hud-kpi-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.hud-kpi-label {
  font-size: 0.75rem;
  color: var(--text-muted);
  font-weight: 600;
}

.hud-kpi-badge {
  font-size: 0.65rem;
  font-weight: 800;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.08);
}
.hud-kpi-badge--up { color: var(--success); background: rgba(74, 222, 128, 0.12); }
.hud-kpi-badge--down { color: var(--primary); background: rgba(0, 242, 254, 0.12); }
.hud-kpi-badge--stable { color: var(--gold); background: rgba(255, 210, 0, 0.12); }

.hud-kpi-val-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.hud-kpi-num {
  font-family: var(--font-mono);
  font-size: 1.4rem;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.02em;
}

.hud-kpi-unit {
  font-size: 0.72rem;
  color: var(--text-muted);
}

.hud-kpi-glow-line {
  position: absolute;
  left: 0; right: 0; bottom: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--primary), transparent);
  opacity: 0.6;
}

/* Main 3-Column HUD Grid */
.hud-layout {
  flex: 1;
  display: grid;
  grid-template-columns: 3.2fr 5.6fr 3.2fr;
  gap: 16px;
  overflow: hidden;
  z-index: 20;
}

.hud-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
  min-height: 0;
}

.hud-col::-webkit-scrollbar { width: 4px; }
.hud-col::-webkit-scrollbar-track { background: rgba(0, 242, 254, 0.02); }
.hud-col::-webkit-scrollbar-thumb { background: rgba(0, 242, 254, 0.2); border-radius: 2px; }

.hud-col--center {
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* Card Styling */
.hud-card {
  position: relative;
  background: rgba(4, 10, 22, 0.75);
  border: 1px solid rgba(0, 242, 254, 0.22);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  backdrop-filter: blur(16px);
  box-shadow: inset 0 0 16px rgba(0, 242, 254, 0.04), 0 8px 24px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: all 0.3s ease;
}

.hud-card:hover,
.hud-card--focal {
  border-color: var(--primary);
  box-shadow: inset 0 0 24px var(--primary-glow), 0 12px 36px rgba(0, 0, 0, 0.7);
}

.hud-card--centerpiece {
  flex: 1.4;
  min-height: 0;
  background: rgba(2, 6, 16, 0.88);
  border-color: rgba(0, 242, 254, 0.35);
}

.hud-card--agent-ticker {
  flex: 0.6;
  min-height: 140px;
}

.hud-card__header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.hud-card__title {
  font-size: 0.92rem;
  font-weight: 800;
  letter-spacing: 0.06em;
  color: #fff;
  margin: 0;
  border-left: 3px solid var(--primary);
  padding-left: 8px;
  text-shadow: 0 0 8px var(--primary-glow);
}

.hud-card__badge {
  font-family: var(--font-mono);
  font-size: 0.64rem;
  font-weight: 800;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid;
  letter-spacing: 0.05em;
}

.hud-card__badge--cyan {
  color: var(--primary);
  background: rgba(0, 242, 254, 0.08);
  border-color: rgba(0, 242, 254, 0.3);
}

.hud-card__badge--purple {
  color: #a855f7;
  background: rgba(168, 85, 247, 0.08);
  border-color: rgba(168, 85, 247, 0.3);
}

.hud-card__badge--green {
  color: var(--success);
  background: rgba(74, 222, 128, 0.08);
  border-color: rgba(74, 222, 128, 0.3);
}

.hud-card__badge--rose {
  color: #f43f5e;
  background: rgba(244, 63, 94, 0.08);
  border-color: rgba(244, 63, 94, 0.3);
}

.hud-card__subtitle {
  font-family: var(--font-mono);
  font-size: 0.66rem;
  color: var(--text-muted);
  margin: 0 0 2px 0;
  letter-spacing: 0.04em;
}

.hud-card__content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  position: relative;
}

.hud-card__content--center {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

/* Corner details */
.hud-header__corner,
.hud-card__corner {
  position: absolute;
  width: 8px;
  height: 8px;
  pointer-events: none;
}
.hud-header__corner--left,
.hud-card__corner--tl {
  left: -1px; top: -1px;
  border-left: 2px solid var(--primary);
  border-top: 2px solid var(--primary);
}
.hud-header__corner--right,
.hud-card__corner--br {
  right: -1px; bottom: -1px;
  border-right: 2px solid var(--primary);
  border-bottom: 2px solid var(--primary);
}

/* Agent Ticker Logs */
.hud-ticker-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hud-ticker-icon { font-size: 1.1rem; }
.hud-ticker-heading {
  font-size: 0.82rem;
  font-weight: 800;
  color: #fff;
  margin: 0;
  letter-spacing: 0.05em;
}

.hud-agent-log-list {
  flex: 1;
  overflow-y: auto;
  font-family: var(--font-mono);
  font-size: 0.72rem;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.hud-agent-log-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.hud-agent-log-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  background: rgba(255, 255, 255, 0.03);
  border-left: 2px solid;
  border-radius: 0 4px 4px 0;
}

.hud-agent-log-item--cyan { border-color: var(--primary); color: #cbf5fd; }
.hud-agent-log-item--purple { border-color: #c084fc; color: #ede9fe; }
.hud-agent-log-item--green { border-color: var(--success); color: #dcfce7; }
.hud-agent-log-item--rose { border-color: #fb7185; color: #ffe4e6; }
.hud-agent-log-item--gold { border-color: var(--gold); color: #fef08a; }

.hud-log-time { color: var(--text-muted); font-size: 0.68rem; }
.hud-log-tag {
  font-weight: 800;
  font-size: 0.62rem;
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.08);
}
.hud-log-agent { font-weight: 700; color: #fff; }
.hud-log-action { flex: 1; color: var(--text-soft); }

.log-slide-enter-active,
.log-slide-leave-active {
  transition: all 0.3s ease;
}
.log-slide-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.log-slide-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

/* Deep Styles for charts inside HUD */
:deep(.hud-dashboard .admin-panel) {
  border: none;
  background: transparent;
  padding: 0;
  box-shadow: none;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}
:deep(.hud-dashboard .admin-panel__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
:deep(.hud-dashboard .admin-panel__title),
:deep(.hud-dashboard .admin-panel__subtitle),
:deep(.hud-dashboard .admin-panel__header) {
  display: none !important;
}

:deep(.hud-dashboard .admin-chart) {
  width: 100% !important;
  height: 100% !important;
  min-height: 180px;
}

:deep(.hud-dashboard .admin-heatmap__map) {
  flex: 1;
  min-height: 0;
  height: 100% !important;
  background: transparent !important;
  border: none;
}
</style>
