/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

// In Docker the backend is reachable as "backend"; locally on localhost.
const apiTarget = process.env.NOVA_API_PROXY_TARGET ?? "http://localhost:8000";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  server: {
    port: 5173,
    // Same-origin API calls: the session cookie never has to cross origins.
    proxy: { "/api": { target: apiTarget, changeOrigin: false, ws: true } },
  },
  build: {
    chunkSizeWarningLimit: 650,
    rollupOptions: {
      output: {
        // Long-term cacheable vendor bundle, separate from app code.
        // (LiveKit is left out on purpose: it is loaded lazily when joining voice.)
        manualChunks: (id) =>
          /node_modules\/(react|react-dom|react-router|scheduler|@tanstack|motion|framer-motion|motion-dom|motion-utils|zustand|lucide-react)\//.test(id)
            ? "vendor"
            : undefined,
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
