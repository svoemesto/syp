// Конфигурация сборки фронтенда админки SYP.
//
// Каталог фронтенда совпадает с именем контейнера `syp-admin-web`
// (Hard Gate Build/Deploy/Containers). В корне репозитория файла
// `package.json` нет: `npm run` из корня не работает намеренно (R-375).
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    // Артефакт сборки отдаёт nginx из контейнера, а не бэкенд.
    emptyOutDir: true,
  },
  server: {
    port: 7912,
    proxy: {
      // В разработке запросы админского API идут в бэкенд напрямую.
      '/api': {
        target: 'http://127.0.0.1:7910',
        changeOrigin: true,
      },
    },
  },
})
