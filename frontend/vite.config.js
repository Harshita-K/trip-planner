import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server proxies /api to the Spring Boot backend, so the browser sees one origin
// (no CORS config needed) and the frontend code uses relative URLs.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // xfwd: pass the browser's address on as X-Forwarded-For, so per-IP rate limits work in dev (D58).
      '/api': { target: 'http://localhost:8080', xfwd: true },
    },
  },
})
