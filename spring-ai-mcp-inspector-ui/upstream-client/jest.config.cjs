module.exports = {
  preset: "ts-jest",
  testEnvironment: "jest-fixed-jsdom",
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/src/$1",
    "\\.css$": "<rootDir>/src/__mocks__/styleMock.js",
    // [spring-ai-mcp-inspector PATCH] marked resolves to its ESM build through
    // package exports, which jest cannot parse without transforming
    // node_modules; resolve the CommonJS build for the test runtime instead
    // (markdown resource preview, issue #170).
    "^marked$": "<rootDir>/node_modules/marked/lib/marked.umd.js",
  },
  transform: {
    "^.+\\.tsx?$": [
      "ts-jest",
      {
        jsx: "react-jsx",
        tsconfig: "tsconfig.jest.json",
      },
    ],
    "^.+\\.m?js$": [
      "ts-jest",
      {
        tsconfig: "tsconfig.jest.json",
      },
    ],
  },
  extensionsToTreatAsEsm: [".ts", ".tsx"],
  transformIgnorePatterns: ["node_modules/(?!(@modelcontextprotocol)/)"],
  testRegex: "(/__tests__/.*|(\\.|/)(test|spec))\\.(jsx?|tsx?)$",
  // Exclude directories and files that don't need to be tested
  testPathIgnorePatterns: [
    "/node_modules/",
    "/dist/",
    "/bin/",
    "/e2e/",
    "\\.config\\.(js|ts|cjs|mjs)$",
    // [spring-ai-mcp-inspector PATCH] Only the upstream *client* is vendored here;
    // upstream's Node proxy (`server/`) is replaced by the Java backend. These two
    // suites read/spawn `../../../../server/src/...` and can never pass in this tree.
    "/src/__tests__/proxyFetchEndpoint\\.test\\.ts$",
    "/src/lib/__tests__/mcpProxyTransportErrorCode\\.test\\.ts$",
  ],
  // Exclude the same patterns from coverage reports
  coveragePathIgnorePatterns: [
    "/node_modules/",
    "/dist/",
    "/bin/",
    "/e2e/",
    "\\.config\\.(js|ts|cjs|mjs)$",
  ],
  randomize: true,
};
