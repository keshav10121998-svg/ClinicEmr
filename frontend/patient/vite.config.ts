import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Patient app on :5174; /api is proxied to the backend on :18080 (same origin, no CORS needed).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/api': 'http://localhost:18080',
    },
  },
});
