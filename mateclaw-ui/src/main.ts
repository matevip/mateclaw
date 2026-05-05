import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './assets/main.css'
import { i18n, initializeLocale } from './i18n'

// Side-effect import: registers the <model-viewer> Web Component globally so
// generated 3D assets (.glb) can be previewed inline in chat bubbles. Vue's
// compiler is told to treat the tag as a custom element via vite.config.ts.
import '@google/model-viewer'

async function bootstrap() {
  await initializeLocale()

  const app = createApp(App)

  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }

  app.use(createPinia())
  app.use(router)
  app.use(i18n)
  app.use(ElementPlus)

  // Global error handler — prevents uncaught Vue errors from causing white screens
  app.config.errorHandler = (err, instance, info) => {
    console.error('[Vue Error]', info, err)
  }

  app.mount('#app')
}

bootstrap()
