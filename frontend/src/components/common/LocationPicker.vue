<script setup lang="ts">
/**
 * 位置选择器（国家 → 省 → 市 → 自定义县/区/街道）
 *
 *  v1：取代之前 MemoryBuilderView 里写死的 32 个 chip。
 *
 *  能力：
 *   1. 中国：联动选择「省/直辖市/特别行政区」→「市/区」，再可手填县/区/街道。
 *   2. 海外：选「国家」→ 输入「城市」，可选输入「区/街道」。
 *   3. 浏览器定位：调用 navigator.geolocation.getCurrentPosition，再走
 *      OpenStreetMap Nominatim 反向地理编码，把当前 GPS 解析成
 *      国家 / 省 / 市 / 县 / 街道，自动回填。
 *
 *  组件以 v-model 暴露一个完整的位置字符串（用空格隔开），形如：
 *    "中国 北京 朝阳"          / "中国 云南 大理 古城区"
 *    "美国 San Francisco"      / "日本 东京 涩谷"
 *  这与 memory-service GeocodingService 现有的 anchor 表能正确命中。
 */
import { computed, ref, watch } from 'vue'
import { CHINA_REGIONS, COUNTRIES } from '../../composables/chinaRegions'

const props = defineProps<{
  modelValue: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', val: string): void
}>()

const country = ref('中国')
const province = ref('')   // 仅当 country === '中国'
const city = ref('')       // 中国：从 CHINA_REGIONS 对应 province.cities 选；海外：自由输入
const detail = ref('')     // 县/区/街道（自由输入）

const locating = ref(false)
const locateError = ref('')

const provinceOptions = computed(() => CHINA_REGIONS.map((p) => p.name))
const cityOptions = computed(() => {
  if (country.value !== '中国') return []
  const p = CHINA_REGIONS.find((x) => x.name === province.value)
  return p?.cities || []
})

/** 把 4 个字段拼成一行，紧凑、能命中后端 GeocodingService。 */
const composed = computed(() => {
  const parts = [country.value, province.value, city.value, detail.value]
    .map((s) => (s || '').trim())
    .filter((s) => s.length > 0)
  return parts.join(' ')
})

watch(composed, (v) => {
  if (v !== props.modelValue) emit('update:modelValue', v)
})

/** 反解析外部传入的初始值（仅在组件首次挂载 / 父组件清空时运行）。 */
watch(
  () => props.modelValue,
  (v) => {
    if (!v || v === composed.value) return
    // 简单还原：按空格切，head 是国家，rest 给 detail
    const tokens = v.trim().split(/\s+/)
    if (tokens.length === 0) return
    if (COUNTRIES.includes(tokens[0])) {
      country.value = tokens[0]
      if (country.value === '中国' && tokens.length >= 2) {
        const provName = CHINA_REGIONS.find((p) => p.name === tokens[1])?.name
        if (provName) {
          province.value = provName
          if (tokens.length >= 3) {
            const cityName = CHINA_REGIONS.find((p) => p.name === provName)
              ?.cities.find((c) => c === tokens[2])
            if (cityName) {
              city.value = cityName
              detail.value = tokens.slice(3).join(' ')
            } else {
              city.value = ''
              detail.value = tokens.slice(2).join(' ')
            }
          } else {
            city.value = ''; detail.value = ''
          }
        } else {
          province.value = ''; city.value = ''; detail.value = tokens.slice(1).join(' ')
        }
      } else {
        province.value = ''; city.value = tokens[1] || ''; detail.value = tokens.slice(2).join(' ')
      }
    } else {
      country.value = '中国'; province.value = ''; city.value = ''; detail.value = v.trim()
    }
  },
  { immediate: true },
)

watch(country, () => {
  if (country.value !== '中国') {
    province.value = ''
  } else if (!province.value && cityOptions.value.length === 0) {
    // 不自动选省，让用户主动选
  }
  city.value = ''
})
watch(province, () => { city.value = '' })

/* ============ 浏览器定位 + Nominatim 反查 ============ */
async function locate() {
  locateError.value = ''
  if (!('geolocation' in navigator)) {
    locateError.value = '浏览器不支持定位 API'
    return
  }
  locating.value = true
  try {
    const pos = await new Promise<GeolocationPosition>((resolve, reject) => {
      navigator.geolocation.getCurrentPosition(resolve, reject, {
        enableHighAccuracy: false,
        timeout: 8000,
        maximumAge: 60_000,
      })
    })
    const { latitude, longitude } = pos.coords
    // Nominatim 公共服务（OpenStreetMap）— accept-language=zh 让返回是中文。
    const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${latitude}&lon=${longitude}&accept-language=zh`
    const resp = await fetch(url, { headers: { 'Accept': 'application/json' } })
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const data = await resp.json()
    const a = data?.address || {}
    const cn = (a.country || '').includes('中国') || a.country_code === 'cn'
    if (cn) {
      country.value = '中国'
      // Nominatim 在中国返回的 state 通常是省名，city / county 是市/区
      const provHit = CHINA_REGIONS.find((p) => (a.state || '').includes(p.name))
      if (provHit) province.value = provHit.name
      const cityFromAddress = a.city || a.county || a.town || a.suburb || ''
      const cityHit = provHit?.cities.find((c) => cityFromAddress.includes(c)) || ''
      city.value = cityHit
      const restParts = [a.suburb, a.neighbourhood, a.road].filter(Boolean) as string[]
      detail.value = restParts.length ? restParts.join('') : ''
    } else {
      country.value = a.country || ''
      province.value = ''
      city.value = a.city || a.town || a.state || ''
      detail.value = [a.suburb, a.neighbourhood, a.road].filter(Boolean).join(' ')
    }
  } catch (e: any) {
    locateError.value = e?.message || '定位失败，请允许浏览器获取位置或手动选择'
  } finally {
    locating.value = false
  }
}
</script>

<template>
  <div class="loc-picker">
    <div class="loc-picker__row">
      <label class="loc-picker__field">
        <span class="loc-picker__label">国家 / 地区</span>
        <select v-model="country" class="select">
          <option v-for="c in COUNTRIES" :key="c" :value="c">{{ c }}</option>
        </select>
      </label>

      <label v-if="country === '中国'" class="loc-picker__field">
        <span class="loc-picker__label">省 / 直辖市</span>
        <select v-model="province" class="select">
          <option value="">请选择…</option>
          <option v-for="p in provinceOptions" :key="p" :value="p">{{ p }}</option>
        </select>
      </label>

      <label v-if="country === '中国' && cityOptions.length" class="loc-picker__field">
        <span class="loc-picker__label">市 / 区</span>
        <select v-model="city" class="select">
          <option value="">请选择…</option>
          <option v-for="c in cityOptions" :key="c" :value="c">{{ c }}</option>
        </select>
      </label>

      <label v-else-if="country !== '中国'" class="loc-picker__field">
        <span class="loc-picker__label">城市</span>
        <input v-model="city" class="input" placeholder="例如：San Francisco / 涩谷" />
      </label>
    </div>

    <label class="loc-picker__field">
      <span class="loc-picker__label">街道 / 详细地址（可选）</span>
      <input v-model="detail" class="input" placeholder="例如：朝阳门外大街 / Market St" maxlength="120" />
    </label>

    <div class="loc-picker__action">
      <button type="button" class="loc-picker__locate" :disabled="locating" @click="locate">
        <span v-if="locating">定位中…</span>
        <span v-else>📍 使用当前位置</span>
      </button>
      <span v-if="composed" class="loc-picker__preview">将保存为：<b>{{ composed }}</b></span>
      <span v-if="locateError" class="loc-picker__error">{{ locateError }}</span>
    </div>
  </div>
</template>

<style scoped>
.loc-picker { display: flex; flex-direction: column; gap: 10px; }
.loc-picker__row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}
.loc-picker__field { display: flex; flex-direction: column; gap: 4px; }
.loc-picker__label {
  font-size: 0.74rem;
  color: var(--text-muted);
  letter-spacing: 0.04em;
}
.loc-picker__action {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  font-size: 0.78rem; color: var(--text-muted);
}
.loc-picker__locate {
  background: rgba(54, 216, 180, 0.12);
  border: 1px solid rgba(54, 216, 180, 0.4);
  color: var(--primary);
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 0.78rem;
  transition: all 180ms ease;
}
.loc-picker__locate:hover:not(:disabled) {
  background: rgba(54, 216, 180, 0.22);
  color: #fff;
}
.loc-picker__locate:disabled { opacity: 0.55; cursor: progress; }
.loc-picker__preview b { color: var(--primary); font-weight: 600; }
.loc-picker__error { color: #ff6b6b; }
</style>
