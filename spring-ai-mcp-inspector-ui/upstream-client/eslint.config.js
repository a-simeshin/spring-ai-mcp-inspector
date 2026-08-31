// Flat ESLint config for the vendored MCP Inspector web client.
//
// The v2.3.0 re-vendor merged upstream's separate lint gates into one tree:
// upstream lints `clients/web/src` with the React plugin stack (this file's
// equivalent) and the shared isomorphic `core/` package from the repository
// root config, which deliberately has NO React plugin (core/ is Node+browser
// TypeScript, no JSX). Since our vendored layout puts `core/` inside this
// package, `eslint .` would otherwise apply the React rules to core/ and fail
// upstream-clean code, so the React blocks are scoped to `src/` exactly the
// way upstream's directory split does.
import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { defineConfig, globalIgnores } from "eslint/config";

// Same "intentionally unused" marker upstream's root config honors for core/.
const sharedUnusedVars = [
  "error",
  {
    argsIgnorePattern: "^_",
    varsIgnorePattern: "^_",
    caughtErrorsIgnorePattern: "^_",
  },
];

export default defineConfig([
  // Generated output and Storybook stories (storybook was dropped from this
  // vendored tree, so nothing typechecks or bundles *.stories.tsx; they stay
  // as dead upstream reference files).
  globalIgnores(["build", "dist", "coverage", "storybook-static", "**/*.stories.{ts,tsx}"]),
  {
    files: ["src/**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  {
    // Test setup files re-export utilities and mix components with helpers
    files: ["src/test/**/*.{ts,tsx}", "src/**/*.test.{ts,tsx}"],
    rules: {
      "react-refresh/only-export-components": "off",
    },
  },
  {
    // core/ is isomorphic TypeScript linted without React rules — mirrors
    // upstream's repository-root eslint.config.js block for core/**.
    files: ["core/**/*.{ts,tsx}"],
    extends: [js.configs.recommended, tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
      globals: {
        ...globals.node,
        ...globals.browser,
      },
    },
    rules: {
      "@typescript-eslint/no-unused-vars": sharedUnusedVars,
    },
  },
  {
    // Type-aware pass for `no-floating-promises`. The parser needs a project
    // that literally contains the linted file: app (src + core), test
    // (src/test, core/__tests__), node (vite.config). `.d.ts` is excluded.
    files: ["**/*.{ts,tsx}"],
    ignores: ["**/*.d.ts"],
    plugins: { "@typescript-eslint": tseslint.plugin },
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: {
        project: [
          "./tsconfig.app.json",
          "./tsconfig.node.json",
          "./tsconfig.test.json",
        ],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      "@typescript-eslint/no-floating-promises": "error",
    },
  },
]);
