import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// One app for clinic staff and patients. In development the backend runs on :18080 and /api is proxied to it, so
// the browser sees one origin (no CORS). The production build is packaged into the backend and served from there.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:18080',
    },
  },
});
