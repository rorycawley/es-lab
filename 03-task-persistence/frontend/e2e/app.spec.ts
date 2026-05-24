import { expect, test } from '@playwright/test';

// AC-06-01
test('submitted title appears in the list within 5 seconds', async ({ page }) => {
  const title = `Browser test ${Date.now()}`;

  await page.goto('/');

  await page.fill('#title', title);
  await page.fill('#description', 'End-to-end browser test');
  await page.click('button[type="submit"]');

  await expect(page.getByText(title)).toBeVisible({ timeout: 5000 });
});
