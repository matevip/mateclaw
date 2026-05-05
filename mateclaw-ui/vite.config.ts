import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // Treat <model-viewer> as a custom Web Component (registered globally
          // via @google/model-viewer in main.ts) so Vue doesn't try to resolve
          // it as a Vue component and emit a "Failed to resolve component"
          // warning at runtime.
          isCustomElement: (tag) => tag === 'model-viewer',
        },
      },
    }),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:18088',
        changeOrigin: true,
        // ws:true forwards WebSocket Upgrade requests through to the backend.
        // Without it Vite serves the GET /api/v1/talk/ws as a regular HTTP
        // proxy, the Upgrade header gets dropped, and the WS handshake
        // silently fails — frontend stuck on "Connecting", backend never
        // sees the connection. TalkMode (STT) lives on this WS so the
        // whole feature is dead in dev mode without it.
        ws: true,
      },
      // Backend-served per-skill bundled assets (logos / screenshots) — see
      // WebMvcConfig.addResourceHandlers. Without this proxy entry vite
      // treats the path as a SPA route and returns index.html, which the
      // browser then tries to render as an image and shows a broken-icon
      // placeholder for any built-in skill that ships a logo.
      '/skill-assets': {
        target: 'http://localhost:18088',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: '../mateclaw-server/src/main/resources/static',
    emptyOutDir: true,
  },
})
