import { test, expect } from '@playwright/test';

test('submits a service request and sees it in the list', async ({ page }) => {
  await page.goto('/');

  await page.getByLabel('Title').fill('Broken printer');
  await page.getByLabel('Description').fill('Printer on 3rd floor is jammed');
  await page.getByRole('button', { name: 'Submit' }).click();

  await expect(page.getByText('Request submitted successfully')).toBeVisible({ timeout: 5000 });
  await expect(page.getByText('Broken printer')).toBeVisible({ timeout: 5000 });
});
