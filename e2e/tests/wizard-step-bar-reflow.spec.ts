import { expect, test } from '../fixtures/auth'
import type { Page } from '@playwright/test'

/**
 * Reflow of the wizard step bar (#2540, WCAG 1.4.10): at a 320 px viewport no assistant that
 * uses it scrolls sideways, every step stays inside the viewport, and every step name is still
 * part of the list's text for screen readers. jsdom computes no layout, hence a browser test.
 */
const VIEWPORT_WIDTH = 320

async function nextStep(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Weiter', exact: true }).click()
}

/** Asserts the current step bar and page fit into the viewport and still name every step. */
async function expectStepBarFits(page: Page, steps: readonly string[], active: string) {
  const bar = page.locator('ol').filter({ has: page.locator('[aria-current="step"]') })
  await expect(bar.locator('[aria-current="step"]')).toContainText(active)

  const items = bar.locator('li')
  await expect(items).toHaveCount(steps.length)
  for (const [index, step] of steps.entries()) {
    // Text content, not visibility: a step shown only by its number keeps its name for AT.
    await expect(items.nth(index), `Schritt ${index + 1} trägt seinen Namen`).toContainText(step)
    const box = await items.nth(index).boundingBox()
    expect(box, `Schritt "${step}" ist gerendert`).not.toBeNull()
    expect(box!.x, `Schritt "${step}" beginnt im Viewport`).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width, `Schritt "${step}" endet im Viewport`).toBeLessThanOrEqual(
      VIEWPORT_WIDTH,
    )
  }

  const overflow = await bar.evaluate((list) => list.scrollWidth - list.clientWidth)
  expect(overflow, 'die Schrittleiste läuft nicht über').toBeLessThanOrEqual(0)

  // String form on purpose: the suite's tsconfig has no DOM lib.
  const scrollWidth = await page.evaluate<number>('document.documentElement.scrollWidth')
  expect(scrollWidth, 'kein horizontaler Überlauf der Seite (WCAG 1.4.10)').toBeLessThanOrEqual(
    VIEWPORT_WIDTH,
  )
}

test.describe('Schrittleiste der Assistenten bei 320 px (#2540)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: VIEWPORT_WIDTH, height: 720 })
  })

  test('Wissens-Assistent mit Upload und mit Konnektor', async ({ authenticatedPage: page }) => {
    await page.goto('/libraries/new')
    await expect(page.getByRole('heading', { level: 2, name: 'Woher kommt das Wissen?' })).toBeVisible()
    await page.getByRole('radio', { name: /^Webverzeichnis/ }).click()
    const connectorSteps = ['Quellart', 'Quelle einrichten', 'Name & Beschreibung', 'Freigaben']
    await expectStepBarFits(page, connectorSteps, 'Quellart')

    await nextStep(page)
    await expect(page.getByRole('heading', { level: 2, name: 'Quelle einrichten' })).toBeVisible()
    await expectStepBarFits(page, connectorSteps, 'Quelle einrichten')

    // The longest step name as the active one: in a compact bar it is the widest state.
    await page.goto('/libraries/new')
    await page.getByRole('radio', { name: /^Upload/ }).click()
    await nextStep(page)
    await expect(page.getByRole('heading', { level: 2, name: 'Name & Beschreibung' })).toBeVisible()
    await expectStepBarFits(page, ['Quellart', 'Name & Beschreibung', 'Freigaben'], 'Name & Beschreibung')
  })

  test('Space-Assistent', async ({ authenticatedPage: page }) => {
    await page.goto('/spaces/new')
    await expectStepBarFits(
      page,
      ['Grunddaten', 'Mitglieder', 'Inhalte', 'Zusammenfassung'],
      'Grunddaten',
    )
  })

  test('Prompt-Bibliothek-Assistent', async ({ authenticatedPage: page }) => {
    await page.goto('/prompts/new')
    await expectStepBarFits(page, ['Stammdaten', 'Eigentümer', 'Rechte'], 'Stammdaten')
  })
})
