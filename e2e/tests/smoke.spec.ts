import { expect, test } from '../fixtures/auth'

test.describe('Rauchtest', () => {
  test('Anwendung öffnen, Testnutzer anmelden, Startseite lädt', async ({ authenticatedPage }) => {
    // The chat page is the landing page after login (see frontend/src/App.tsx).
    // Its input placeholder is a stable, user-visible marker that the app
    // shell rendered successfully.
    await expect(
      authenticatedPage.getByPlaceholder('Stellen Sie eine Frage …'),
    ).toBeVisible()
  })
})
