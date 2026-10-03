import { expect, test } from '../fixtures/auth'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { apiAs } from '../fixtures/externalAccess'
import { createPromptLibraryViaApi, escapeRegExp } from '../fixtures/promptLibraries'

/** A space of dev-admin's own, so the scenario associates nothing in a space others rely on. */
async function createSpaceViaApi(name: string): Promise<string> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.post('/api/v1/spaces', { data: { name } })
    expect(response.status(), await response.text()).toBe(201)
    return ((await response.json()) as { id: string }).id
  } finally {
    await api.dispose()
  }
}

/**
 * The tab "Inhalte" of the space settings (#2130): one tile list for every asset type, a check
 * mark that takes effect at once, operated by keyboard, its confirmation announced, axe clean.
 */
test.describe('Space-Einstellungen: Reiter „Inhalte“', () => {
  test('Häkchen per Tastatur ordnet sofort zu, Abhaken lässt sich rückgängig machen', async ({
    authenticatedPage: page,
  }) => {
    const stamp = Date.now()
    const libraryName = `E2E-Inhalte-Vorlagen-${stamp}`
    await createPromptLibraryViaApi('dev-admin', { name: libraryName })
    const spaceId = await createSpaceViaApi(`E2E-Inhalte-Space-${stamp}`)

    // The former tab still leads here.
    await page.goto(`/spaces/${spaceId}/settings/prompts`)
    await expect(page).toHaveURL(new RegExp(`/spaces/${spaceId}/settings/content$`))
    await expect(page.getByRole('tab', { name: 'Inhalte', selected: true })).toBeVisible()
    await expect(
      page.getByText('Ein Chat in diesem Space nutzt nur, was hier ausgewählt ist.'),
    ).toBeVisible()
    const onlyAssociated = page.getByRole('button', { name: 'Nur zugeordnete' })
    await expect(onlyAssociated).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByText(/Diesem Space ist noch nichts zugeordnet/)).toBeVisible()

    await page.emulateMedia({ colorScheme: 'light' })
    await expectNoSeriousA11yViolations(page, 'Space-Einstellungen, Inhalte (helles Farbschema)')

    await onlyAssociated.click()
    await page.getByRole('searchbox', { name: 'Suche' }).fill(libraryName)
    const tile = page.getByRole('checkbox', { name: new RegExp(`^${escapeRegExp(libraryName)}`) })
    await expect(tile).toHaveAttribute('aria-checked', 'false')

    await tile.focus()
    await page.keyboard.press('Space')
    const notice = page.getByRole('alert').filter({ hasText: libraryName })
    await expect(notice).toContainText('zugeordnet')
    await expect(tile).toHaveAttribute('aria-checked', 'true')
    await expect(tile).toBeFocused()

    await page.emulateMedia({ colorScheme: 'dark' })
    await expectNoSeriousA11yViolations(page, 'Space-Einstellungen, Inhalte (dunkles Farbschema)')

    await page.keyboard.press('Space')
    const detached = page.getByRole('alert').filter({ hasText: 'gelöst' })
    await expect(detached).toBeVisible()
    await expect(tile).toHaveAttribute('aria-checked', 'false')

    await detached.getByRole('button', { name: 'Rückgängig' }).click()
    await expect(tile).toHaveAttribute('aria-checked', 'true')

    // A reload shows what the server holds: the association survived the undo.
    await page.reload()
    await expect(
      page.getByRole('checkbox', { name: new RegExp(`^${escapeRegExp(libraryName)}`) }),
    ).toHaveAttribute('aria-checked', 'true')
  })
})
