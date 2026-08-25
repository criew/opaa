import { expect, test } from '../fixtures/auth'

/**
 * #707: Vite bündelt Assets unter 4 KB als data:-URIs; die nginx-CSP erlaubt aber nur
 * `font-src 'self'`. Die kleinen Quicksand-Subsets landeten dadurch als geblockte data:-Fonts
 * im CSS - sechs Konsolenverstöße auf jeder Seite, betroffene Unicode-Bereiche fielen auf die
 * Ersatzschrift zurück. Der Test lädt die App durch die echte nginx-Auslieferung des Stacks
 * und sammelt CSP-Verstöße aus der Konsole ein - rot auf dem Altstand, grün sobald der Build
 * Fonts als Dateien emittiert (vite.config.ts, assetsInlineLimit).
 */
test.describe('Content Security Policy (#707)', () => {
  test('Konsole ohne CSP-Verstöße beim Laden der App', async ({ browser }) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    const violations: string[] = []
    page.on('console', (message) => {
      if (message.text().includes('Content Security Policy')) {
        violations.push(message.text())
      }
    })

    await page.goto('/?devUser=dev-admin')
    await expect(
      page.getByPlaceholder('Frage stellen … mit @ auf eine Quelle eingrenzen'),
    ).toBeVisible()

    expect(violations, 'CSP-Verstöße in der Browser-Konsole').toEqual([])
    await context.close()
  })
})
