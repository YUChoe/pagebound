import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 개발 서버는 API·첨부·로그인 요청을 Spring Boot(8080)로 넘긴다
const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  build: {
    // Spring Boot 정적 리소스로 포함 (gitignore 대상)
    outDir: '../pagebound-server/src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 3000,
  },
  server: {
    proxy: {
      '/api': backend,
      '/p/': backend,
      '^/w/.*\\.md$': backend,
      '/logout': backend,
      '/login': { target: backend, bypass: (req) => (req.method === 'GET' ? '/index.html' : undefined) },
    },
  },
  test: {
    environment: 'jsdom',
    testTimeout: 120000,
  },
})
