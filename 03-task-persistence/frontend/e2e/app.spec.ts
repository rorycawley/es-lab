import { test, expect } from '@playwright/test';

test('submits a service request and sees it in the list', async ({ page }) => {
  const title = `Broken printer ${Date.now()}`;

  await page.goto('/');

  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Description').fill('Printer on 3rd floor is jammed');
  await page.getByRole('button', { name: 'Submit' }).click();

  await expect(page.getByText('Request submitted successfully')).toBeVisible({ timeout: 5000 });
  await expect(page.getByText(title)).toBeVisible({ timeout: 5000 });

  await page.getByLabel('Search requests').fill(title);
  await expect(page.getByText(title)).toBeVisible({ timeout: 5000 });

  await page.getByLabel('Search requests').fill(`no match ${Date.now()}`);
  await expect(page.getByText('No matching requests.')).toBeVisible({ timeout: 5000 });

  const unfilteredTitle = `Door repair ${Date.now()}`;

  await page.getByLabel('Title').fill(unfilteredTitle);
  await page.getByLabel('Description').fill('Door 201 is stuck');
  await page.getByRole('button', { name: 'Submit' }).click();

  await expect(page.getByText('Request submitted successfully')).toBeVisible({ timeout: 5000 });
  await expect(page.getByLabel('Search requests')).toHaveValue('');
  await expect(page.getByText(unfilteredTitle)).toBeVisible({ timeout: 5000 });
});
