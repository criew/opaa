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
    await page.goto('/admin/users')
    const column = page.getByRole('navigation', { name: 'Administration' })
    await expect(column).toBeVisible()

    await column.getByRole('link', { name: 'Modelle' }).click()
    await page.waitForURL('**/admin/models')
    await expect(page.getByRole('heading', { level: 1, name: 'Modelle' })).toBeVisible()

    // #1542: der Eintrag „E-Mail" landet auf dem Server-Tab, der selbst eine Route ist.
    await column.getByRole('link', { name: 'E-Mail' }).click()
    await page.waitForURL('**/admin/mail/server')
    await expect(page.getByRole('heading', { level: 1, name: 'E-Mail' })).toBeVisible()
    await expect(
      page.getByRole('tab', { name: 'SMTP-Zugang' }).and(page.locator('[aria-selected="true"]')),
    ).toBeVisible()

    // #1541: „Benutzer & Gruppen" sind zwei Einträge; „Benutzer" ist zugleich das Ziel des
    // Admin-Einstiegs in der globalen Leiste.
    await column.getByRole('link', { name: 'Benutzer', exact: true }).click()
    // #1601: „Benutzer" hat zwei Bereiche als eigene Routen; der nackte Pfad leitet auf die Konten.
    await page.waitForURL('**/admin/users/accounts')
    await expect(page.getByRole('heading', { level: 1, name: 'Benutzer' })).toBeVisible()
    await expect(page.getByRole('searchbox', { name: 'Konten suchen' })).toBeVisible()
    await page.getByRole('tab', { name: 'Einstellungen' }).click()
    await page.waitForURL('**/admin/users/settings')
    await expect(page.getByRole('switch', { name: 'Lokale Anmeldung aktiv' })).toBeVisible()

    await column.getByRole('link', { name: 'Gruppen', exact: true }).click()
    await page.waitForURL('**/admin/groups')
    await expect(page.getByRole('heading', { level: 1, name: 'Gruppen' })).toBeVisible()

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

    // "Suche & Indexierung" is the longest of the four labels and therefore the one that
    // decides whether the column still fits into 320 px (#1053).
    for (const label of [
      'Allgemein & Branding',
      'Benutzer',
      'Gruppen',
      'Modelle',
      'E-Mail',
      'Suche & Indexierung',
    ]) {
      const box = await column
        .getByRole('link', { name: label, exact: true })
        .boundingBox()
      expect(box, `Ziel "${label}" ist gerendert`).not.toBeNull()
      expect(box!.x, `Ziel "${label}" beginnt im Viewport`).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width, `Ziel "${label}" endet im Viewport`).toBeLessThanOrEqual(320)
    }
  })
})
