import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The browser only ever talks to the API gateway. In development the dev server proxies /api to it, so the
// gateway port is read from GATEWAY_PORT (the same variable the backend uses, default 8080).
// VITE_API_BASE overrides this and points the app at a gateway URL directly (CORS must then allow this origin).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const gatewayPort = env.GATEWAY_PORT || process.env.GATEWAY_PORT || '8080';
  const proxy = { '/api': { target: `http://localhost:${gatewayPort}`, changeOrigin: false } };
  return {
    plugins: [react(), tailwindcss()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, proxy },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test-setup.js'],
      css: false,
    },
  };
});
