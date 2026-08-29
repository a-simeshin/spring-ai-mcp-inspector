/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createBrowserExternalizedBuiltinGate } from "./server/browser-externalized-builtin-gate";

const dirname =
  typeof __dirname !== "undefined"
    ? __dirname
    : path.dirname(fileURLToPath(import.meta.url));

const sharedAliases = {
  "@inspector/core": path.resolve(dirname, "core"),
};

const sharedDedupe = [
  "react",
  "react-dom",
  "@modelcontextprotocol/client",
  "@modelcontextprotocol/core",
];

const nodeModulesAliases = [];

// Fail `vite build` when a Node built-in lands in the browser bundle (#1769).
function browserExternalizedBuiltinGate(): Plugin {
  const gate = createBrowserExternalizedBuiltinGate();
  return {
    name: "inspector:fail-on-browser-externalized-builtin",
    apply: "build",
    applyToEnvironment: (environment) => environment.name === "client",
    enforce: "pre",
    buildStart() {
      gate.reset();
    },
    onLog(_level, log) {
      gate.recordLog(log.message);
    },
    buildEnd(error) {
      if (!error) gate.assertClean();
    },
  };
}

export default defineConfig(() => {
  return {
    base: "/mcp-inspector/",
    plugins: [
      react(),
      browserExternalizedBuiltinGate(),
    ],
    resolve: {
      alias: [
        ...Object.entries(sharedAliases).map(([find, replacement]) => ({
          find,
          replacement,
        })),
        ...nodeModulesAliases,
      ],
      dedupe: sharedDedupe,
    },
    build: {
      outDir: "dist",
      emptyOutDir: true,
      rolldownOptions: {
        external: ["@modelcontextprotocol/ext-apps/app-bridge", "pino/browser.js"],
      },
    },
    test: {
      name: "unit",
      environment: "happy-dom",
      environmentOptions: {
        happyDOM: {
          settings: { navigation: { disableChildFrameNavigation: true } },
        },
      },
      include: ["src/**/*.test.{ts,tsx}"],
      exclude: ["src/test/integration/**"],
      setupFiles: [path.join(dirname, "src/test/setup.ts")],
      sequence: { hooks: "stack" },
    },
  };
});