import react from "@vitejs/plugin-react";
import path from "path";
import { defineConfig } from "vite";

// https://vitejs.dev/config/
export default defineConfig({
  // Base public path — every emitted asset URL is prefixed with this. The Spring
  // backend mounts the bundle under /mcp-inspector/, so we have to match.
  // This literal is duplicated in Java as BootstrapHtmlRenderer.BUNDLE_ASSET_BASE,
  // which rewrites it at serve time when the app runs under a context path, a
  // WebFlux base path, or a custom spring.ai.mcp.inspector.path. Keep both in sync.
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
