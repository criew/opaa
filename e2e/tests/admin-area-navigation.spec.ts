import { expect, test } from '../fixtures/auth'

/**
 * Navigation durch den Verwaltungsbereich (#787/#800) - hierher aus accessibility.spec.ts
 * verschoben (#805): die Szenarien führen keine axe-Analyse aus, sie sichern Erreichbarkeit
 * und Geometrie der Admin-Sekundärspalte.
 */
test.describe('Verwaltungsbereich: Navigation über die Sekundärspalte (#787)', () => {
  // #800 (Review zu #794): kein Spec klickte durch die Admin-Sekundärspalte. Der Durchklick
  // sichert das Abnahmekriterium "Wechsel zwischen den Admin-Seiten über die Sekundärspalte"
  // (#787) - aber nur am Desktop-Viewport: die mobile Erreichbarkeit sichert er ausdrücklich
  // NICHT (Playwright scrollt vor jedem Klick per scrollIntoViewIfNeeded, der Klick gelänge
  // auch auf einem überlaufenden Layout - nachgemessen im Review zu #803). Dafür steht der
  // Geometrie-Test darunter.
  test('Wechsel über die Sekundärspalte', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/groups')
    const column = page.getByRole('navigation', { name: 'Administration' })
    await expect(column).toBeVisible()

    await column.getByRole('link', { name: 'Modelle' }).click()
    await page.waitForURL('**/admin/models')
    await expect(page.getByRole('heading', { level: 1, name: 'Modelle' })).toBeVisible()

    await column.getByRole('link', { name: 'Allgemein & Branding' }).click()
    await page.waitForURL('**/admin/branding')
    await expect(page.getByRole('heading', { level: 1, name: 'Branding' })).toBeVisible()
  })

  // #805: die 320-px-Zusage aus #800 ("ein 320px-Viewport zeigt jedes Ziel") als Geometrie-
  // Zusicherung statt als Durchklick - Klicks beweisen hier nichts, weil Playwright vor jedem
  // Klick scrollt. Rot, sobald der Mobil-Umbruch der Sekundärspalte zurückgebaut wird.
  test('320 px: kein horizontaler Überlauf, jedes Ziel im Viewport', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 320, height: 640 })
    await page.goto('/admin/groups')
    const column = page.getByRole('navigation', { name: 'Administration' })
    await expect(column).toBeVisible()

    // String form on purpose: the suite's tsconfig has no DOM lib (tests run in Node,
    // only this expression runs in the browser).
    const scrollWidth = await page.evaluate<number>('document.documentElement.scrollWidth')
    expect(scrollWidth, 'kein horizontaler Überlauf der Seite (WCAG 1.4.10)').toBeLessThanOrEqual(
      320,
    )

    for (const label of ['Allgemein & Branding', 'Benutzer & Gruppen', 'Modelle']) {
      const box = await column.getByRole('link', { name: label }).boundingBox()
      expect(box, `Ziel "${label}" ist gerendert`).not.toBeNull()
      expect(box!.x, `Ziel "${label}" beginnt im Viewport`).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width, `Ziel "${label}" endet im Viewport`).toBeLessThanOrEqual(320)
    }
  })
})
