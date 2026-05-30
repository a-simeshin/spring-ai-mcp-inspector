import react from "@vitejs/plugin-react";
import path from "path";
import { defineConfig } from "vite";

// https://vitejs.dev/config/
export default defineConfig({
  // Base public path — every emitted asset URL is prefixed with this. The Spring
  // backend mounts the bundle under /mcp-inspector/, so we have to match.
  base: "/mcp-inspector/",
  plugins: [react()],
  server: {
    host: true,
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    minify: false,
    rollupOptions: {
      output: {
        manualChunks: undefined,
      },
    },
  },
});
