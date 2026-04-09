import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'MateClawWebChat',
      formats: ['es', 'umd'],
      fileName: (format) => `mateclaw-webchat.${format}.js`,
    },
    rollupOptions: {
      output: {
        assetFileNames: 'mateclaw-webchat.[ext]',
      },
    },
    cssCodeSplit: false,
    minify: 'esbuild',
  },
})
