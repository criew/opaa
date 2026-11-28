import { defineConfig } from "@playwright/test";

/**
 * Playwright configuration of the failover test (deploy/helm/ci/failover-test.sh): one scenario
 * per call, a disturbance of the replicated database or of a node under load, measured and written
 * to E2E_FAILOVER_RESULTS. One worker, no retry: the run leaves the cluster changed.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 20 * 60_000,
  expect: {
    timeout: 30_000,
  },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  outputDir: "../test-results-failover",
  reporter: [["list"]],
});
