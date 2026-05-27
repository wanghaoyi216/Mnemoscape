export interface User {
  id: string
  username: string
  email: string
  avatarUrl?: string
  verified: boolean
  createdAt?: string
  updatedAt?: string
}

export interface AuthResponse {
  userId: string
  username: string
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export interface MemoryAttachment {
  /** MinIO 对象 key 或可直连的 presigned URL */
  url: string
  /** 'image' | 'video' | 'audio' */
  kind: string
  mime?: string
  objectKey?: string
  /** 命中 MinIO presigned URL 时由 asset-service 透传 */
  ttl?: number
}

export interface MemoryItem {
  id: string
  userId: string
  title: string
  description: string
  memoryYear?: number
  memoryDate?: string
  memorySeason?: string
  memoryTimeOfDay?: string
  memoryLocation?: string
  /** [lng, lat] when geocoded by memory-service; absent for unresolved locations */
  coords?: [number, number]
  privacyLevel: 'PRIVATE' | 'FRIENDS' | 'PUBLIC'
  isLocked: boolean
  fadeLevel: number
  sceneDataUrl?: string
  emotionVectorId?: string
  /** AI 创建期 freeze 的完整 SceneReconstructionResponse JSON 字符串。
   *  非空 → SceneViewer 直接 JSON.parse 用，无需重跑 /reconstruct（避免每次卡 30s）。 */
  visualData?: string
  /** AI 创建期 freeze 的 emotion vector JSON 字符串。 */
  emotionProfile?: string
  attachments?: MemoryAttachment[]
  createdAt: string
  updatedAt: string
}

export interface MemoryVersion {
  id: string
  memoryId: string
  versionNumber: number
  changeType: string
  changeDescription: string
  snapshotData: string
  createdAt: string
}

export interface MemoryFragment {
  id: string
  memoryId: string
  fragmentType: 'forgotten_detail' | 'emotion_flashback' | 'linked_door'
  content: string
  position3d: string
  triggerCondition: string
  isDiscovered: boolean
}

export interface DriftState {
  fadeLevel: number
  daysSinceCreation: number
  colorSaturation: number
  fogDensity: number
  audioReverb: number
}

export interface SceneLighting {
  type: string
  color: string
  intensity: number
}

export interface SceneTerrain {
  type: string
  color: string
}

export interface SceneAtmosphere {
  fogColor: string
  fogDensity: number
  backgroundColor: string
}

export interface SceneAudioSource {
  type: string
  file: string
  volume: number
  loop?: boolean
  position?: number[]
}

export interface SceneObject {
  id: string
  type: string
  name?: string
  position: number[]
  color: string
  scale: number[]
}

export interface SceneFragment {
  fragmentType: string
  content: string
  position3d: { x: number; y: number; z: number }
  triggerRadius: number
  isDiscovered: boolean
}

export interface SceneData {
  environment: string
  lighting: SceneLighting
  terrain: SceneTerrain
  atmosphere: SceneAtmosphere
  objects: SceneObject[]
  audioData: {
    ambient: SceneAudioSource[]
    positional: SceneAudioSource[]
  }
  fragments: SceneFragment[]
}

export interface SceneReconstructionResponse {
  sceneData: SceneData
  audioData: SceneData['audioData']
  emotionVector: Record<string, number>
  sensoryDetails: Record<string, string>
  fragments: SceneFragment[]
  sceneDataUrl: string
}

export interface ResonanceMatch {
  memoryId: string
  title: string
  similarityScore: number
  emotionSimilarity: number
  sceneSimilarity: number
  ownerUsername: string
}

export interface ResonanceSpace {
  id: string
  memoryId1: string
  memoryId2: string
  similarityScore: number
  emotionSimilarity: number
  sceneSimilarity: number
  sceneDataUrl: string
  status: string
  createdAt: string
}

export interface MemoryNote {
  id: string
  resonanceId: string
  authorId: string
  content: string
  mood: string
  position3d: string
  createdAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  requestId?: string
}

export interface WebSocketMessage {
  type: 'JOIN' | 'LEAVE' | 'MOVE' | 'PLACE_NOTE' | 'GHOST_JOIN' | 'GHOST_LEFT' | 'GHOST_MOVE' | 'NOTE_PLACED'
  resonanceId: string
  userId?: string
  position?: number[]
  lookingAt?: { x: number; y: number; z: number }
  content?: string
  mood?: string
}
