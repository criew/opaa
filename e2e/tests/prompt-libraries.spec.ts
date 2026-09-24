import { expect, test } from '../fixtures/auth'
import { startFreshChat } from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import {
  accessibleCatalogEntry,
  createKnowledgeLibraryViaApi,
  createPromptLibraryViaApi,
  createReleasedGroupViaApi,
  escapeRegExp,
  gotoPromptLibraries,
  gotoPromptLibraryDetail,
  listedCatalogEntry,
  searchCatalog,
} from '../fixtures/promptLibraries'
import type { Page } from '@playwright/test'

// Unique per run, so a stack that was not torn down never offers a leftover of the same name.
const runId = Date.now()
const LIBRARY_NAME = `E2E Prompt-Bibliothek ${runId}`
const PROMPT_TITLE = `Anhörung ${runId}`
const PROMPT_COMMAND = `anhoerung-${runId.toString(36)}`
const PROMPT_TEXT = 'Entwirf ein Anhörungsschreiben zum Aktenzeichen {{aktenzeichen}}.'
const RESOLVED_TEXT = 'Entwirf ein Anhörungsschreiben zum Aktenzeichen AZ 50-123.'
const GROUP_NAME = `E2E Prompt-Gruppe ${runId}`
const LISTED_LIBRARY_NAME = `E2E Gelistete Prompts ${runId}`
const LISTED_KNOWLEDGE_NAME = `E2E Gelistetes Wissen ${runId}`

/** The owner's own view of the library, found by name - the id the outsider must never reach. */
async function promptLibraryId(name: string): Promise<string> {
  const api = await apiAs('dev-user')
  try {
    const response = await api.get('/api/v1/prompt-libraries')
    expect(response.status()).toBe(200)
    const libraries = (await response.json()) as Array<{ id: string; name: string }>
    const library = libraries.find((candidate) => candidate.name === name)
    expect(library, `Prompt-Bibliothek „${name}“ fehlt`).toBeDefined()
    return library!.id
  } finally {
    await api.dispose()
  }
}

async function openManagement(page: Page, libraryName: string): Promise<void> {
  await gotoPromptLibraryDetail(page, libraryName)
  await page.getByRole('tab', { name: 'Verwaltung' }).click()
}

async function saveRelease(page: Page): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        /\/api\/v1\/prompt-libraries\/[^/]+$/.test(new URL(response.url()).pathname) &&
        response.ok(),
    ),
    page.getByRole('button', { name: 'Freigabe speichern' }).click(),
  ])
}

async function expectNotInPromptList(page: Page, name: string): Promise<void> {
  await gotoPromptLibraries(page)
  await expect(page.getByRole('heading', { level: 1, name: 'Prompts' })).toBeVisible()
  await expect(page.getByText(name, { exact: true })).toHaveCount(0)
}

async function expectInPromptList(page: Page, name: string): Promise<void> {
  await gotoPromptLibraries(page)
  await expect(page.getByText(name, { exact: true })).toBeVisible()
}

/**
 * Covers test(e2e) #1904, the evidence of Epic #1726: a person without any system role creates a
 * prompt library with a prompt and a required variable, gives it to a person, to a group and to the
 * whole organization - and a person it was never given to finds nothing of it, not in the list, not
 * in the catalog, not at its address. The catalog finds a listed asset of either type without
 * opening it, and a person the organization-wide release reaches inserts the prompt in the chat.
 *
 * dev-user owns everything here. dev-admin receives the person grant and dev-format-pipelines is
 * the one member of the group: dev-outsider must stay unrelated until the organization-wide
 * release. A second organization is not reachable in the dev auth mode of this suite - the
 * organization boundary is covered by the backend's AssetCatalogServiceIntegrationTest.
 */
test.describe.serial('Prompt-Bibliotheken: Verteilungsstufen und Katalog (#1904)', () => {
  test('1. Prompt-Bibliothek mit einem Prompt und einer Pflicht-Variable anlegen', async ({
    regularUserPage: owner,
  }) => {
    await owner.goto('/prompts/new')
    await owner.getByLabel(/^Name/).fill(LIBRARY_NAME)
    await owner.getByRole('button', { name: 'Weiter', exact: true }).click()
    await owner.getByRole('button', { name: 'Weiter zu Rechten' }).click()
    await expect(owner.getByLabel('Im Katalog auffindbar')).not.toBeChecked()
    await owner.getByRole('button', { name: 'Prompt-Bibliothek anlegen' }).click()
    await expect(owner.getByRole('heading', { level: 1, name: LIBRARY_NAME })).toBeVisible()

    await owner.getByRole('button', { name: 'Neuer Prompt' }).click()
    const dialog = owner.getByRole('dialog', { name: 'Neuer Prompt' })
    await dialog.getByLabel('Titel', { exact: true }).fill(PROMPT_TITLE)
    await dialog.getByLabel('Befehl', { exact: true }).fill(PROMPT_COMMAND)
    await dialog.getByLabel('Text', { exact: true }).fill(PROMPT_TEXT)
    await dialog.getByLabel('Beschriftung von aktenzeichen').fill('Aktenzeichen')
    await dialog.getByLabel('aktenzeichen ist Pflicht').check()
    await dialog.getByRole('button', { name: 'Prompt speichern' }).click()
    await expect(dialog).toHaveCount(0)

    await expect(owner.getByText(`/${PROMPT_COMMAND}`)).toBeVisible()
    await expect(owner.getByText('1 Variable, davon 1 Pflicht')).toBeVisible()
  })

  test('2. Freigabe an eine Person', async ({ regularUserPage: owner, authenticatedPage: admin }) => {
    // Administering is not reading: without a grant the system administration does not list it.
    await expectNotInPromptList(admin, LIBRARY_NAME)

    await openManagement(owner, LIBRARY_NAME)
    await owner.getByRole('button', { name: 'Rechte verwalten' }).click()
    await owner.getByRole('button', { name: 'Freigeben' }).click()
    const personInput = owner.getByRole('combobox', { name: 'Person suchen' })
    await personInput.click()
    await personInput.fill('Dev Admin')
    await owner.getByRole('option', { name: /Dev Admin/ }).click()
    await owner.getByRole('button', { name: 'Freigeben' }).last().click()
    await expect(owner.getByRole('dialog').getByText(/Dev Admin/)).toBeVisible()
    await owner.getByRole('dialog').getByRole('button', { name: 'Schließen' }).click()

    await expectInPromptList(admin, LIBRARY_NAME)
    await gotoPromptLibraryDetail(admin, LIBRARY_NAME)
    await expect(admin.getByText(`/${PROMPT_COMMAND}`)).toBeVisible()
  })

  test('3. Freigabe an eine Gruppe', async ({
    regularUserPage: owner,
    formatPipelinesPage: member,
  }) => {
    await createReleasedGroupViaApi(GROUP_NAME, 'Dev Format Pipelines')
    await expectNotInPromptList(member, LIBRARY_NAME)

    await openManagement(owner, LIBRARY_NAME)
    await owner.getByRole('button', { name: 'Rechte verwalten' }).click()
    await owner.getByRole('button', { name: 'Freigeben' }).click()
    await owner.getByRole('radio', { name: 'Gruppe' }).click()
    const groupInput = owner.getByRole('combobox', { name: 'Gruppe suchen' })
    await groupInput.click()
    await groupInput.fill(GROUP_NAME)
    await owner.getByRole('option', { name: GROUP_NAME }).click()
    await owner.getByRole('button', { name: 'Freigeben' }).last().click()
    await expect(owner.getByRole('dialog').getByText(GROUP_NAME)).toBeVisible()
    await owner.getByRole('dialog').getByRole('button', { name: 'Schließen' }).click()

    await expectInPromptList(member, LIBRARY_NAME)
  })

  test('4. Negativfall: ohne Freigabe weder Liste noch Katalog noch Detail', async ({
    outsiderPage: outsider,
  }) => {
    const id = await promptLibraryId(LIBRARY_NAME)

    await expectNotInPromptList(outsider, LIBRARY_NAME)

    await searchCatalog(outsider, LIBRARY_NAME)
    await expect(accessibleCatalogEntry(outsider, LIBRARY_NAME)).toHaveCount(0)
    await expect(listedCatalogEntry(outsider, LIBRARY_NAME)).toHaveCount(0)

    await outsider.goto(`/prompts/${id}`)
    await expect(outsider.getByRole('alert')).toContainText('nicht gefunden')
    await expect(outsider.getByText(`/${PROMPT_COMMAND}`)).toHaveCount(0)
  })

  test('5. Organisationsweit freigeben', async ({
    regularUserPage: owner,
    outsiderPage: outsider,
  }) => {
    await openManagement(owner, LIBRARY_NAME)
    await owner.getByRole('combobox', { name: 'Verteilungsstufe' }).click()
    await owner.getByRole('option', { name: 'organisationsweit' }).click()
    await saveRelease(owner)

    await expectInPromptList(outsider, LIBRARY_NAME)
    await searchCatalog(outsider, LIBRARY_NAME)
    const entry = accessibleCatalogEntry(outsider, LIBRARY_NAME)
    await expect(entry).toBeVisible()
    await expect(entry).toContainText('Prompt-Bibliothek')
    await expect(entry).toContainText('zuständig: Dev User')
    await entry.click()
    await expect(outsider.getByRole('heading', { level: 1, name: LIBRARY_NAME })).toBeVisible()
    await expect(outsider.getByText(`/${PROMPT_COMMAND}`)).toBeVisible()
  })

  test('6. Im Katalog auffindbar erst nach der Listung - ohne Zugriff', async ({
    regularUserPage: owner,
    outsiderPage: outsider,
  }) => {
    const id = await createPromptLibraryViaApi('dev-user', {
      name: LISTED_LIBRARY_NAME,
      description: 'Bescheidbausteine nach Hausstandard',
    })

    await searchCatalog(outsider, LISTED_LIBRARY_NAME)
    await expect(listedCatalogEntry(outsider, LISTED_LIBRARY_NAME)).toHaveCount(0)

    await openManagement(owner, LISTED_LIBRARY_NAME)
    await owner.getByLabel('Im Katalog auffindbar').check()
    await saveRelease(owner)

    await searchCatalog(outsider, LISTED_LIBRARY_NAME)
    const entry = listedCatalogEntry(outsider, LISTED_LIBRARY_NAME)
    await expect(entry).toBeVisible()
    await expect(entry).toContainText('Bescheidbausteine nach Hausstandard')
    await expect(entry).toContainText('Auffindbar ohne Berechtigung — zuständig: Dev User')
    await expect(accessibleCatalogEntry(outsider, LISTED_LIBRARY_NAME)).toHaveCount(0)

    // Findable is not accessible: neither the list nor the address opens it.
    await expectNotInPromptList(outsider, LISTED_LIBRARY_NAME)
    await outsider.goto(`/prompts/${id}`)
    await expect(outsider.getByRole('alert')).toContainText('nicht gefunden')
  })

  test('7. Katalog: gelistete Wissensbibliothek ohne Zugriff, Typfilter', async ({
    outsiderPage: outsider,
  }) => {
    await createKnowledgeLibraryViaApi('dev-user', {
      name: LISTED_KNOWLEDGE_NAME,
      description: 'Satzungen und Dienstanweisungen',
      listed: true,
    })

    await searchCatalog(outsider, runId.toString())
    const knowledge = listedCatalogEntry(outsider, LISTED_KNOWLEDGE_NAME)
    await expect(knowledge).toBeVisible()
    await expect(knowledge).toContainText('Wissensbibliothek')
    await expect(knowledge).toContainText('Auffindbar ohne Berechtigung — zuständig: Dev User')
    await expect(listedCatalogEntry(outsider, LISTED_LIBRARY_NAME)).toBeVisible()
    await expect(accessibleCatalogEntry(outsider, LIBRARY_NAME)).toBeVisible()

    await Promise.all([
      outsider.waitForResponse(
        (response) =>
          new URL(response.url()).searchParams.get('type') === 'KNOWLEDGE_LIBRARY' &&
          response.ok(),
      ),
      outsider.getByRole('button', { name: 'Wissen', exact: true }).click(),
    ])
    await expect(knowledge).toBeVisible()
    await expect(listedCatalogEntry(outsider, LISTED_LIBRARY_NAME)).toHaveCount(0)
    await expect(accessibleCatalogEntry(outsider, LIBRARY_NAME)).toHaveCount(0)
  })

  test('8. Im Chat per Befehl einsetzen, mit Variablenformular und Hinweis im Verlauf', async ({
    outsiderPage: outsider,
  }) => {
    await startFreshChat(outsider)
    const input = outsider.getByPlaceholder('Nachricht eingeben …')
    await input.fill(`/${PROMPT_COMMAND}`)
    await outsider
      .getByRole('option', { name: new RegExp(escapeRegExp(`/${PROMPT_COMMAND}`)) })
      .click()

    const dialog = outsider.getByRole('dialog', { name: `Prompt einsetzen: ${PROMPT_TITLE}` })
    await expect(dialog.getByRole('button', { name: 'Einsetzen' })).toBeDisabled()
    await dialog.getByLabel(/Aktenzeichen/).fill('AZ 50-123')
    await dialog.getByRole('button', { name: 'Einsetzen' }).click()
    await expect(dialog).toHaveCount(0)
    // Inserting is not sending: the resolved text waits in the input.
    await expect(input).toHaveValue(RESOLVED_TEXT)

    // The answer is what persists the turn; a reload before it would find no chat yet.
    const [answer] = await Promise.all([
      outsider.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/query',
      ),
      outsider.getByRole('button', { name: 'Senden' }).click(),
    ])
    expect(answer.status()).toBe(200)
    await outsider.waitForURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    await expect(outsider.getByTestId('used-prompt')).toContainText(`Prompt: ${PROMPT_TITLE}`)

    // The note belongs to the history, not to the session that sent it.
    await outsider.reload()
    await expect(outsider.getByText(RESOLVED_TEXT)).toBeVisible()
    await expect(outsider.getByTestId('used-prompt')).toContainText(`Prompt: ${PROMPT_TITLE}`)
  })
})
