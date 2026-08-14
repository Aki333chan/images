import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // dev: API и WS через прокси, чтобы cookie были first-party
      '/api': { target: 'http://localhost:3001', changeOrigin: true },
      '/ws': { target: 'http://localhost:3001', ws: true, changeOrigin: true },
    },
  },
});
