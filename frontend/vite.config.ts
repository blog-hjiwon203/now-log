/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // blog.test:5173으로 연다 (/etc/hosts, quickstart). 블로그는 blog.test:5173/@주소
    allowedHosts: ['.blog.test'],
    // API와 업로드 이미지는 Spring Boot(8080)로 넘긴다. 블로그는 X-Blog-Address 헤더로 알린다
    proxy: {
      '/api': 'http://localhost:8080',
      '/uploads': 'http://localhost:8080',
      // 블로그 RSS 피드(/@주소/rss)는 화면이 아니라 서버가 만드는 XML이다. ^로 시작하는 키는 정규식이다
      '^/@[^/]+/rss$': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
    // npm run test -- --coverage 일 때만 잰다. SonarQube가 coverage/lcov.info를 읽는다
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
    },
  },
})
