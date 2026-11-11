import { expect, test } from '../fixtures/auth'

/**
 * Navigation durch den Verwaltungsbereich (#787/#800) - hierher aus accessibility.spec.ts
 * verschoben: die Szenarien führen keine axe-Analyse aus, sie sichern Erreichbarkeit
 * und Geometrie der Admin-Sekundärspalte.
 */
test.describe('Verwaltungsbereich: Navigation über die Sekundärspalte (#787)', { tag: '@browser-smoke' }, () => {
  // Der Durchklick durch die Admin-Sekundärspalte
  // sichert das Abnahmekriterium "Wechsel zwischen den Admin-Seiten über die Sekundärspalte"
  // - aber nur am Desktop-Viewport: die mobile Erreichbarkeit sichert er ausdrücklich
  // NICHT (Playwright scrollt vor jedem Klick per scrollIntoViewIfNeeded, der Klick gelänge
  // auch auf einem überlaufenden Layout). Dafür steht der
  // Geometrie-Test darunter.
  test('Wechsel über die Sekundärspalte', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users')
    const column = page.getByRole('navigation', { name: 'Administration' })
    await expect(column).toBeVisible()

    await column.getByRole('link', { name: 'Modelle' }).click()
    // The bare path forwards to the chat models at once, so only the target is certain to be seen.
    await page.waitForURL('**/admin/models/chat')
    await expect(page.getByRole('heading', { level: 1, name: 'Modelle' })).toBeVisible()

    // Der Eintrag „E-Mail" landet auf dem Server-Tab, der selbst eine Route ist.
    await column.getByRole('link', { name: 'E-Mail' }).click()
    await page.waitForURL('**/admin/mail/server')
    await expect(page.getByRole('heading', { level: 1, name: 'E-Mail' })).toBeVisible()
    await expect(
      page.getByRole('tab', { name: 'SMTP-Zugang' }).and(page.locator('[aria-selected="true"]')),
    ).toBeVisible()

    // „Benutzer & Gruppen" sind zwei Einträge; „Benutzer" ist zugleich das Ziel des
    // Admin-Einstiegs in der globalen Leiste.
    await column.getByRole('link', { name: 'Benutzer', exact: true }).click()
    // „Benutzer" hat zwei Bereiche als eigene Routen; der nackte Pfad leitet auf die Konten.
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

  // Unter 900 px steht die Bereichsnavigation als eine waagerecht scrollbare Zeile über der Seite
  // (Leitlinie 4.7). Geometrie statt Durchklick: Playwright scrollt vor jedem Klick, ein Klick
  // bewiese hier nichts.
  test('320 px: kein horizontaler Überlauf, Navigation in einer Zeile, aktiver Eintrag sichtbar', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 320, height: 640 })
    await page.goto('/admin/groups')
    const nav = page.getByRole('navigation', { name: 'Administration' })
    await expect(nav).toBeVisible()

    // String form on purpose: the suite's tsconfig has no DOM lib (tests run in Node,
    // only this expression runs in the browser).
    const scrollWidth = await page.evaluate<number>('document.documentElement.scrollWidth')
    expect(scrollWidth, 'kein horizontaler Überlauf der Seite (WCAG 1.4.10)').toBeLessThanOrEqual(
      320,
    )

    const links = nav.getByRole('link')
    const count = await links.count()
    expect(count, 'alle Einträge der Verwaltung stehen in der Zeile').toBeGreaterThan(10)
    const firstBox = await links.first().boundingBox()
    const lastBox = await links.last().boundingBox()
    expect(firstBox, 'erster Eintrag gerendert').not.toBeNull()
    expect(lastBox, 'letzter Eintrag gerendert').not.toBeNull()
    expect(Math.abs(lastBox!.y - firstBox!.y), 'alle Einträge in einer Zeile').toBeLessThan(2)

    const current = nav.getByRole('link', { name: 'Gruppen', exact: true })
    await expect(current).toHaveAttribute('aria-current', 'page')
    const box = await current.boundingBox()
    expect(box, 'aktiver Eintrag gerendert').not.toBeNull()
    expect(box!.x, 'aktiver Eintrag beginnt im Viewport').toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width, 'aktiver Eintrag endet im Viewport').toBeLessThanOrEqual(320)

    const heading = await page.getByRole('heading', { level: 1, name: 'Gruppen' }).boundingBox()
    expect(heading, 'Seitentitel gerendert').not.toBeNull()
    expect(heading!.y, 'Inhalt beginnt oben (Leitlinie 4.7)').toBeLessThanOrEqual(200)

    // Unter 600 px steht der Anlege-Knopf in voller Breite unter dem Titel, unter 900 px ist jede
    // Liste eine Kartenliste.
    const create = await page.getByRole('button', { name: 'Gruppe anlegen' }).boundingBox()
    expect(create, 'Anlege-Knopf gerendert').not.toBeNull()
    expect(create!.y, 'Anlege-Knopf unter dem Titel').toBeGreaterThanOrEqual(
      heading!.y + heading!.height - 1,
    )
    expect(create!.width, 'Anlege-Knopf in voller Breite').toBeGreaterThan(320 - 2 * 24 - 1)
    await expect(page.locator('table:visible')).toHaveCount(0)
  })
})
