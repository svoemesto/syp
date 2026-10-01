// Конфигурация сборки фронтенда публичной части SYP.
//
// Каталог фронтенда совпадает с именем контейнера `syp-public-web`
// (Hard Gate Build/Deploy/Containers).
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 7913,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:7911',
        changeOrigin: true,
      },
    },
  },
})
