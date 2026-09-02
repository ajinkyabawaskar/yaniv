import { defineConfig, devices } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Playwright E2E Test Configuration for Yaniv
 * Tests both frontend and backend integration
 */

/**
 * The dev server's port, resolved the same way Create React App resolves it:
 * a real environment variable wins, then frontend/.env, then CRA's own default
 * of 3000. Parsed by hand because dotenv is only a transitive dependency here.
 *
 * scripts/run-all-tests.sh resolves the port the same way; keep the two in step.
 */
function portFromEnvFile(): string | undefined {
  try {
    const line = fs
      .readFileSync(path.join(__dirname, '.env'), 'utf8')
      .split('\n')
      .map(l => l.trim())
      .filter(l => l && !l.startsWith('#'))
      .reverse()
      .find(l => l.startsWith('PORT='));
    const value = line?.slice('PORT='.length).trim().replace(/^["']|["']$/g, '');
    return value || undefined;
  } catch {
    // No .env, or unreadable: fall through to the default.
    return undefined;
  }
}

const PORT = process.env.PORT?.trim() || portFromEnvFile() || '3000';
const BASE_URL = `http://localhost:${PORT}`;

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
    baseURL: BASE_URL,
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
    url: BASE_URL,
    reuseExistingServer: true,
    timeout: 120000,
    env: {
      // Pin the spawned dev server to the port we're waiting on, so an ambient
      // PORT and frontend/.env can never disagree with BASE_URL.
      PORT,
      REACT_APP_API_URL: 'http://localhost:8080/api/v1',
      REACT_APP_WS_URL: 'http://localhost:8080/ws',
    },
  },
});