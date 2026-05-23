import { test, expect } from '@playwright/test';

test('shows healthy when backend is running', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('Project is confirmed to be healthy')).toBeVisible({ timeout: 10000 });
});
