import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  // Publication changes global rules; run these scenarios after ordinary business checks.
  projects: [
    { name: 'business', testIgnore: /governance-api\.spec\.ts/ },
    { name: 'rule-and-batch', testMatch: /governance-api\.spec\.ts/, dependencies: ['business'] },
  ],
  use: {
    baseURL: process.env.BASE_URL || 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined },
  },
  reporter: 'list',
})
