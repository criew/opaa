import { expect, test } from '../fixtures/auth'

test.describe('Rauchtest', () => {
  test('Anwendung als Testnutzer öffnen, Startseite lädt', async ({ authenticatedPage }) => {
    // The chat page is the landing page (see frontend/src/App.tsx). Its input
    // placeholder is a stable, user-visible marker that the app shell rendered
    // successfully.
    await expect(
      authenticatedPage.getByPlaceholder('Frage stellen … mit @ auf eine Quelle eingrenzen'),
    ).toBeVisible()
  })
})
