import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue(),
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
    },
  },
  build: {
    outDir: '../mateclaw-server/src/main/resources/static',
    emptyOutDir: true,
  },
})
