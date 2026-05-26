import { ref } from 'vue'

const isOffline = ref(false)

export function useHealthCheck() {
  return {
    isOffline,
    setOffline(status: boolean) {
      isOffline.value = status
    }
  }
}
