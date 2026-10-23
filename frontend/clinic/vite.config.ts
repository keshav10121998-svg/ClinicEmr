import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend runs on :18080; the dev server proxies /api so the browser sees one origin (no CORS needed).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:18080',
    },
  },
});
