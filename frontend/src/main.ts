import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import i18n from './i18n'
import { vReveal } from './directives/reveal'
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(i18n)
app.directive('reveal', vReveal)
app.mount('#app')
