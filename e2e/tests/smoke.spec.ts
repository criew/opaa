import { expect, test } from '../fixtures/auth'
import { gotoLibraries, gotoLibraryDetail } from '../fixtures/chat'

test.describe('Rauchtest', () => {
  test('Anwendung als Testnutzer öffnen, Startseite lädt', async ({ authenticatedPage }) => {
    // The chat page is the landing page (see frontend/src/App.tsx). Its input
    // placeholder is a stable, user-visible marker that the app shell rendered
    // successfully.
    await expect(
      authenticatedPage.getByPlaceholder('Frage stellen … mit @ auf eine Quelle eingrenzen'),
    ).toBeVisible()
  })

  /**
   * Covers test(e2e) #233 (PR #726 review, finding 2): every other scenario in this suite only
   * ever *reads* demo/seed/profiles.py's own imports and comments, never the seed's actual output
   * - a broken grant, upload or indexing step in the "e2e" data profile would go entirely
   * unnoticed. `regularUserPage` is `dev-user`, the seed's own VIEWER on "E2E Wissensbibliothek" -
   * this asserts on the library and document the seed run (scripts/run-e2e.mjs, before Playwright
   * even starts) is supposed to have produced, not on anything this test uploads itself.
   */
  test('Seed-Daten (#233): E2E Wissensbibliothek ist für dev-user gelistet und ihr Basisdokument indiziert', async ({
    regularUserPage: page,
  }) => {
    await gotoLibraries(page)
    await expect(page.getByText('E2E Wissensbibliothek', { exact: true })).toBeVisible()

    await gotoLibraryDetail(page, 'E2E Wissensbibliothek')
    await expect(page.getByText('e2e-basisdokument.txt', { exact: true })).toBeVisible()
    await expect(page.getByText('indiziert')).toBeVisible()
  })
})
