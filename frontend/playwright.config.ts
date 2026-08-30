import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E Test Configuration for Yaniv
 * Tests both frontend and backend integration
 */
export default defineConfig({
  testDir: './e2e',
  // Suites share seated pages across tests (host seats, others join) - keep
  // tests within a file strictly ordered; only files run in parallel.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : 2,
  timeout: 120_000,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    // Test with mobile viewport
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 5'] },
    },
  ],
  webServer: {
    command: 'npm run start',
    url: 'http://localhost:3000',
    reuseExistingServer: true,
    timeout: 120000,
    env: {
      REACT_APP_API_URL: 'http://localhost:8080/api/v1',
      REACT_APP_WS_URL: 'http://localhost:8080/ws',
    },
  },
});