<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { createOrUpdateAvatarProfile, deleteAvatarProfile, type AvatarProfile } from '../../api/avatar'

const props = defineProps<{
  existingProfile: AvatarProfile | null
}>()

const emit = defineEmits<{
  (e: 'saved', profile: AvatarProfile): void
  (e: 'deleted'): void
}>()

const { t } = useI18n()

const selfDescription = ref(props.existingProfile?.selfDescription || '')
const isPublic = ref(props.existingProfile?.isPublic ?? false)
const saving = ref(false)
const deleting = ref(false)
const errorMsg = ref('')
const showDeleteConfirm = ref(false)

const charCount = computed(() => selfDescription.value.length)
const isValid = computed(() => charCount.value >= 10 && charCount.value <= 1000)

const charCountClass = computed(() => {
  if (charCount.value < 10) return 'count--warn'
  if (charCount.value > 900) return 'count--warn'
  return 'count--ok'
})

// 描述质量提示
const qualityHint = computed(() => {
  const len = charCount.value
  if (len === 0) return ''
  if (len < 10) return t('profile.avatar3d.builder.hints.tooShort')
  if (len < 30) return t('profile.avatar3d.builder.hints.minimal')
  if (len < 80) return t('profile.avatar3d.builder.hints.good')
  return t('profile.avatar3d.builder.hints.excellent')
})

const qualityClass = computed(() => {
  const len = charCount.value
  if (len < 10) return 'hint--warn'
  if (len < 30) return 'hint--info'
  return 'hint--ok'
})

async function handleSave() {
  if (!isValid.value || saving.value) return
  saving.value = true
  errorMsg.value = ''
  try {
    const { data } = await createOrUpdateAvatarProfile({
      selfDescription: selfDescription.value.trim(),
      isPublic: isPublic.value,
    })
    emit('saved', data.data)
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || t('profile.avatar3d.builder.error.saveFailed')
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (deleting.value) return
  deleting.value = true
  errorMsg.value = ''
  try {
    await deleteAvatarProfile()
    showDeleteConfirm.value = false
    emit('deleted')
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || t('profile.avatar3d.builder.error.deleteFailed')
  } finally {
    deleting.value = false
  }
}

// 示例描述（帮助用户理解如何填写）
const exampleDescriptions = [
  '我是一个喜欢在深夜读书的人，内心有一片安静的星空，偶尔会在凌晨三点望着窗外发呆，思考一些没有答案的问题。',
  '热爱旅行和摄影，用镜头记录每一个值得被记住的瞬间。阳光、海浪和陌生城市的街道让我感到自由。',
  '音乐是我的语言，每一首歌都是一段记忆的索引。我喜欢在雨天听老歌，让旋律把我带回那些已经远去的时光。',
]

function useExample(example: string) {
  selfDescription.value = example
}
</script>

<template>
  <div class="builder-card">
    <div class="builder-header">
      <p class="eyebrow">{{ t('profile.avatar3d.builder.eyebrow') }}</p>
      <h3 class="builder-title">{{ t('profile.avatar3d.builder.title') }}</h3>
      <p class="builder-subtitle">{{ t('profile.avatar3d.builder.subtitle') }}</p>
    </div>

    <!-- 示例描述 -->
    <div class="example-section">
      <span class="example-label">{{ t('profile.avatar3d.builder.exampleLabel') }}</span>
      <div class="example-chips">
        <button
          v-for="(ex, i) in exampleDescriptions"
          :key="i"
          type="button"
          class="example-chip"
          @click="useExample(ex)"
        >
          {{ t('profile.avatar3d.builder.exampleBtn', { n: i + 1 }) }}
        </button>
      </div>
    </div>

    <!-- 描述输入 -->
    <div class="field">
      <label class="field__label">
        {{ t('profile.avatar3d.builder.descLabel') }}
        <span :class="['char-count', charCountClass]">{{ charCount }} / 1000</span>
      </label>
      <textarea
        v-model="selfDescription"
        class="textarea"
        :placeholder="t('profile.avatar3d.builder.descPlaceholder')"
        rows="5"
        maxlength="1000"
      ></textarea>
      <p v-if="qualityHint" :class="['quality-hint', qualityClass]">{{ qualityHint }}</p>
    </div>

    <!-- 公开设置 -->
    <label class="toggle-row">
      <input type="checkbox" v-model="isPublic" class="toggle-checkbox" />
      <span class="toggle-label">{{ t('profile.avatar3d.builder.publicLabel') }}</span>
      <span class="toggle-hint">{{ t('profile.avatar3d.builder.publicHint') }}</span>
    </label>

    <!-- 错误提示 -->
    <transition name="alert">
      <p v-if="errorMsg" class="error-msg" role="alert">{{ errorMsg }}</p>
    </transition>

    <!-- 操作按钮 -->
    <div class="builder-actions">
      <button
        type="button"
        class="button button--primary"
        :disabled="!isValid || saving"
        @click="handleSave"
      >
        <span v-if="saving" class="spinner"></span>
        {{ saving
          ? t('profile.avatar3d.builder.saving')
          : existingProfile
            ? t('profile.avatar3d.builder.regenerate')
            : t('profile.avatar3d.builder.generate') }}
      </button>

      <button
        v-if="existingProfile"
        type="button"
        class="button button--ghost button--danger"
        :disabled="deleting"
        @click="showDeleteConfirm = true"
      >
        {{ t('profile.avatar3d.builder.delete') }}
      </button>
    </div>

    <!-- 删除确认 -->
    <transition name="fade">
      <div v-if="showDeleteConfirm" class="confirm-overlay" @click.self="showDeleteConfirm = false">
        <div class="confirm-card">
          <p class="confirm-text">{{ t('profile.avatar3d.builder.deleteConfirm') }}</p>
          <div class="confirm-actions">
            <button
              type="button"
              class="button button--danger"
              :disabled="deleting"
              @click="handleDelete"
            >
              {{ deleting ? t('common.loading') : t('common.confirm') }}
            </button>
            <button
              type="button"
              class="button button--ghost"
              @click="showDeleteConfirm = false"
            >
              {{ t('common.cancel') }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.builder-card {
  padding: 24px;
  background: rgba(14, 17, 22, 0.55);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  backdrop-filter: blur(12px);
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.builder-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.builder-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text);
  margin: 0;
}

.builder-subtitle {
  font-size: 0.82rem;
  color: var(--text-soft);
  margin: 0;
  line-height: 1.5;
}

.example-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.example-label {
  font-size: 0.75rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.example-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.example-chip {
  font-size: 0.75rem;
  color: var(--primary);
  background: rgba(54, 216, 180, 0.08);
  border: 1px solid rgba(54, 216, 180, 0.2);
  border-radius: 999px;
  padding: 4px 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.example-chip:hover {
  background: rgba(54, 216, 180, 0.16);
  border-color: var(--primary);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field__label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.char-count {
  font-size: 0.72rem;
  font-variant-numeric: tabular-nums;
}

.count--ok { color: var(--primary); }
.count--warn { color: #f59e0b; }

.textarea {
  width: 100%;
  min-height: 120px;
  padding: 12px;
  background: rgba(8, 10, 14, 0.6);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  color: var(--text);
  font-size: 0.88rem;
  line-height: 1.6;
  resize: vertical;
  transition: border-color 0.2s;
  font-family: inherit;
}

.textarea:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 2px rgba(54, 216, 180, 0.12);
}

.quality-hint {
  font-size: 0.75rem;
  margin: 0;
  padding: 4px 0;
}

.hint--warn { color: #f59e0b; }
.hint--info { color: var(--text-soft); }
.hint--ok { color: var(--primary); }

.toggle-row {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.toggle-checkbox {
  width: 16px;
  height: 16px;
  accent-color: var(--primary);
  cursor: pointer;
}

.toggle-label {
  font-size: 0.85rem;
  color: var(--text);
}

.toggle-hint {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.error-msg {
  font-size: 0.8rem;
  color: #f87171;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.2);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  margin: 0;
}

.builder-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.button--danger {
  color: #f87171;
  border-color: rgba(248, 113, 113, 0.3);
}

.button--danger:hover {
  background: rgba(248, 113, 113, 0.1);
  border-color: #f87171;
}

.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  margin-right: 6px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 删除确认弹窗 */
.confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}

.confirm-card {
  background: rgba(14, 17, 22, 0.95);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 24px;
  max-width: 360px;
  width: 90%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.confirm-text {
  font-size: 0.9rem;
  color: var(--text);
  margin: 0;
  line-height: 1.5;
}

.confirm-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

/* Transitions */
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
.alert-enter-active, .alert-leave-active { transition: all 0.25s; }
.alert-enter-from, .alert-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
