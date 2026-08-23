import { expect, test } from '../fixtures/auth'
import { askQuestion, startFreshChat } from '../fixtures/chat'
import type { Locator, Page } from '@playwright/test'

/**
 * Modellverwaltung über den vollen Stack (#760, Epic #755): Anlegen, Verbindungstest, Aktivieren
 * und der Löschschutz für das aktive Modell - die eine Kette, die über die Ticketgrenzen von
 * #756-#758 verteilt war und bislang nur schichtweise getestet ist. Besonders die Zusicherung "der
 * API-Schlüssel kommt nie zurück" (Szenario 6) und "eine Aktivierung wirkt ohne Neustart"
 * (Szenario 2, #758) sind nur im vollständigen Durchlauf überprüfbar.
 *
 * `test.describe.serial`: alle Szenarien teilen sich das eine im Compose-Stack seit dem Seed-Lauf
 * aktive Chat-Modell (io.opaa.llm.LlmModelSeedRunner übernimmt beim Erststart die
 * OPAA_OPENAI_*-Konfiguration aus e2e.env, die auf ai-stub zeigt - siehe #766/#767) und wechseln es
 * für die Dauer dieser Datei bewusst um; parallele oder umsortierte Ausführung würde das
 * durcheinanderbringen. `test.afterAll` aktiviert danach exakt das ursprünglich aktive Modell
 * wieder (nicht bloß irgendein funktionierendes) - genau die im Issue geforderte Rückaktivierung,
 * ohne die jeder spätere Chat-Test in dieser Suite (z. B. space-chats.spec.ts) auf einem von diesem
 * Lauf hinterlassenen Modell säße.
 *
 * Kein echter Ollama-Server im Compose-Stack der Suite (anders als die Ausgangsannahme des
 * Issues): docker-compose.yml/e2e/docker-compose.e2e.yml starten keinen `ollama`-Dienst (siehe
 * deployment.md, "OPAA_OLLAMA_BASE_URL" - der Compose-Stack enthält keinen ollama-Service, ein
 * erreichbarer Server wird nur für einen echten Betrieb vorausgesetzt). Der positive
 * Verbindungstest zielt deshalb auf `ai-stub` - denselben OpenAI-kompatiblen Ersatz, den auch das
 * beim Seed übernommene Modell schon verwendet (siehe oben) - statt auf eine in dieser Suite gar
 * nicht existierende Ollama-Adresse.
 */

const DEV_USER_HEADER = 'X-OPAA-Dev-User'

// Unique per run, wie in den übrigen Specs (siehe z. B. knowledge-libraries.spec.ts), damit ein
// erneuter lokaler Lauf gegen einen nicht abgebauten Stack nicht mit Modellen einer vorigen
// Ausführung kollidiert.
const runId = Date.now()
const CONNECTION_TEST_MODEL_NAME = `E2E Verbindungstest-Modell ${runId}`
const KEY_MODEL_NAME = `E2E Modell mit Schluessel ${runId}`

// ai-stub beantwortet jeden Pfad, der auf "/chat/completions" endet, unabhängig vom "model"-Feld
// im Request-Body (siehe e2e/ai-stub/server.mjs) - die Modell-Kennung ist hier daher frei wählbar.
const REACHABLE_BASE_URL = 'http://ai-stub:8089/v1'
const MODEL_IDENTIFIER = 'e2e-stub-chat'

// ".invalid" ist laut RFC 2606 reserviert und löst nie auf - deterministisch fehlschlagend, ganz
// ohne auf tatsächliche Netzwerk-/DNS-Gegebenheiten der CI-Umgebung angewiesen zu sein.
const UNREACHABLE_BASE_URL = 'http://opaa-e2e-unreachable.invalid:9999/v1'

interface LlmModelApiResponse {
  id: string
  displayName: string
  baseUrl: string
  modelIdentifier: string
  apiKeySet: boolean
  active: boolean
}

async function fetchModels(page: Page): Promise<LlmModelApiResponse[]> {
  const response = await page.request.get('/api/v1/admin/models', {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  expect(response.ok()).toBe(true)
  return (await response.json()) as LlmModelApiResponse[]
}

async function gotoModelManagement(page: Page): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && response.url().endsWith('/api/v1/admin/models'),
    ),
    page.goto('/admin/models'),
  ])
  await expect(page.getByRole('heading', { name: 'Modelle' })).toBeVisible()
  // Observed locally: a model card's Accordion (MUI Collapse) can settle at an intermediate
  // height instead of completing its expand transition - `aria-expanded` still flips to "true" (a
  // plain prop, unaffected), but fields further down the form never actually become visible that
  // way, hanging a scenario on a timeout. Disabling all CSS transitions/animations on this page
  // removes that class of animation-timing flakiness entirely, without touching any product
  // behaviour under test - this suite is about the model management flow, not Accordion motion.
  await page.addStyleTag({
    content: '*, *::before, *::after { transition: none !important; animation: none !important; }',
  })
}

/**
 * Scopes every assertion/interaction for one specific model to its own card, keyed by id rather
 * than by display name or role name. Necessary, not just nice-to-have: `AccordionDetails` stays
 * mounted in the DOM while collapsed (only its height animates), so every card's own "API-
 * Schlüssel" field, "Aktiv"-Chip, etc. are simultaneously present for every model on the page at
 * once - a page-wide `getByLabel`/`getByRole` query without this scope resolves to one element
 * per model instead of the one this test actually means (a real strict-mode violation observed
 * locally, not a hypothetical). `data-testid="llm-model-card-<id>"` on the card's `Accordion`
 * root (`LlmModelCard`, #760) is the one non-role/label selector this file needs - added in this
 * same PR and actually used here, per e2e/README.md's "Selektor-Konvention".
 */
function modelCard(page: Page, modelId: string): Locator {
  return page.getByTestId(`llm-model-card-${modelId}`)
}

/** Expands modelId's card (idempotent) and waits for the expansion to have actually completed. */
async function expandModelCard(page: Page, modelId: string): Promise<void> {
  const card = modelCard(page, modelId)
  const summary = card.getByRole('button', { expanded: false })
  if ((await summary.count()) > 0) {
    await summary.click()
  }
  await expect(card.getByRole('button', { expanded: true })).toBeVisible()
}

test.describe.serial('Modellverwaltung (#760)', () => {
  let originalActiveModelId: string
  let connectionTestModelId: string | undefined
  let keyModelId: string | undefined

  test.beforeAll(async ({ request }) => {
    const response = await request.get('/api/v1/admin/models', {
      headers: { [DEV_USER_HEADER]: 'dev-admin' },
    })
    expect(response.ok()).toBe(true)
    const models = (await response.json()) as LlmModelApiResponse[]
    const active = models.find((model) => model.active)
    if (!active) {
      throw new Error(
        'Kein aktives Chat-Modell zu Beginn des Laufs gefunden - die Seed-Voraussetzung ' +
          '(genau ein aktives Modell nach dem Erststart) ist verletzt.',
      )
    }
    originalActiveModelId = active.id
  })

  test.afterAll(async ({ request }) => {
    await request.post(`/api/v1/admin/models/${originalActiveModelId}/activate`, {
      headers: { [DEV_USER_HEADER]: 'dev-admin' },
    })
    for (const modelId of [connectionTestModelId, keyModelId]) {
      if (modelId) {
        await request.delete(`/api/v1/admin/models/${modelId}`, {
          headers: { [DEV_USER_HEADER]: 'dev-admin' },
        })
      }
    }
  })

  test('1. Anlegen ohne Schlüssel, Verbindungstest und Aktivieren', async ({
    authenticatedPage: page,
  }) => {
    await gotoModelManagement(page)

    await page.getByRole('button', { name: 'Neues Modell' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Anzeigename', { exact: false }).fill(CONNECTION_TEST_MODEL_NAME)
    await dialog.getByLabel('Basis-Adresse', { exact: false }).fill(REACHABLE_BASE_URL)
    await dialog.getByLabel('Modell-Kennung', { exact: false }).fill(MODEL_IDENTIFIER)
    // Bewusst kein API-Schlüssel eingetragen - "Anlegen ohne API-Key möglich" ist Teil des Umfangs.

    await dialog.getByRole('button', { name: 'Verbindung testen' }).click()
    await expect(dialog.getByText(/Verbindung erfolgreich/i)).toBeVisible()

    const [createResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' && response.url().endsWith('/api/v1/admin/models'),
      ),
      dialog.getByRole('button', { name: 'Anlegen' }).click(),
    ])
    expect(createResponse.ok()).toBe(true)
    const created = (await createResponse.json()) as LlmModelApiResponse
    connectionTestModelId = created.id
    expect(created.apiKeySet).toBe(false)
    await expect(page.getByRole('dialog')).not.toBeVisible()

    const card = modelCard(page, created.id)
    await expect(card).toBeVisible()
    await expandModelCard(page, created.id)
    await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith(`/api/v1/admin/models/${created.id}/activate`),
      ),
      card
        .getByRole('button', { name: `"${CONNECTION_TEST_MODEL_NAME}" als aktives Modell setzen` })
        .click(),
    ])

    // "Aktiv setzen" verschwindet ausschließlich für das jetzt aktive Modell (LlmModelCard rendert
    // den Button nur, solange !model.active) - und der Chip erscheint stattdessen.
    await expect(
      card.getByRole('button', {
        name: `"${CONNECTION_TEST_MODEL_NAME}" als aktives Modell setzen`,
      }),
    ).toHaveCount(0)
    await expect(card.getByLabel('Aktives Modell')).toBeVisible()

    const models = await fetchModels(page)
    const active = models.filter((model) => model.active)
    expect(active).toHaveLength(1)
    expect(active[0]?.id).toBe(created.id)
  })

  test('2. Eine Chat-Anfrage beantwortet nach der Aktivierung ohne Neustart', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)
    // Ohne Wissensbasis (kein @-Bezug) ist die Antwort selbst deterministisch - der Punkt dieses
    // Szenarios ist der Modellwechsel ohne Neustart (#758), nicht die inhaltliche Qualität der
    // Antwort (siehe Issue, "Technische Hinweise").
    await page.getByRole('button', { name: 'Referenz Alles-Wissen entfernen' }).press('Backspace')
    await expect(page.getByText('Antwortet ohne Dokumente.')).toBeVisible()

    await askQuestion(page, `Testfrage nach Modellaktivierung (${runId})`)

    await expect(page.getByText('Diese Antwort wurde ohne Wissensbasis erstellt.')).toBeVisible()
  })

  test('3. Ein Nutzer ohne SYSTEM_ADMIN sieht die Modellverwaltung nicht', async ({
    regularUserPage: page,
  }) => {
    await expect(page.getByRole('link', { name: 'Modelle' })).toHaveCount(0)

    await page.goto('/admin/models')
    await expect(page.getByText(/nicht freigegeben/i)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Neues Modell' })).toHaveCount(0)
  })

  test('4. Löschen des aktiven Modells ist gesperrt', async ({ authenticatedPage: page }) => {
    await gotoModelManagement(page)
    const card = modelCard(page, connectionTestModelId!)
    await expandModelCard(page, connectionTestModelId!)

    const deleteButton = card.getByRole('button', {
      name: `"${CONNECTION_TEST_MODEL_NAME}" löschen`,
    })
    await expect(deleteButton).toHaveAttribute('aria-disabled', 'true')
    await expect(card.getByText(/aktive Modell kann nicht gelöscht werden/i)).toBeVisible()

    // Nicht nur die UI-Sperre - auch serverseitig abgelehnt (409), falls die Anfrage die
    // client-seitige Sperre umginge.
    const response = await page.request.delete(`/api/v1/admin/models/${connectionTestModelId}`, {
      headers: { [DEV_USER_HEADER]: 'dev-admin' },
    })
    expect(response.status()).toBe(409)
  })

  test('5. Verbindungstest gegen eine nicht erreichbare Adresse zeigt eine Fehlermeldung', async ({
    authenticatedPage: page,
  }) => {
    await gotoModelManagement(page)
    await page.getByRole('button', { name: 'Neues Modell' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Basis-Adresse', { exact: false }).fill(UNREACHABLE_BASE_URL)
    await dialog.getByLabel('Modell-Kennung', { exact: false }).fill(MODEL_IDENTIFIER)

    await dialog.getByRole('button', { name: 'Verbindung testen' }).click()
    // Welche konkrete Fehlerursache LlmModelConnectionTester meldet, hängt vom Netzwerkverhalten
    // der Umgebung ab: eine DNS-Auflösung für ".invalid" (RFC 2606) schlägt lokal deterministisch
    // fehl, kann aber je nach Resolver innerhalb des Compose-Netzwerks auch auf eine sofortige
    // Ablehnung (ECONNREFUSED) statt auf "Host nicht gefunden" hinauslaufen - beobachtet in diesem
    // Lauf. Beides ist eine gültige, sichtbare deutsche Fehlermeldung im Sinn des Szenarios; erfasst
    // wird deshalb "eine Fehlermeldung ungleich Erfolg", nicht ein konkreter Wortlaut.
    const testOutcome = dialog.getByRole('alert')
    await expect(testOutcome).toBeVisible()
    await expect(testOutcome).not.toContainText(/erfolgreich/i)

    // Anlegen bleibt trotzdem möglich - der fehlgeschlagene Test allein blockiert nichts. Hier aber
    // bewusst abgebrochen, damit dieses Szenario kein weiteres, aufzuräumendes Modell hinterlässt.
    await dialog.getByRole('button', { name: 'Abbrechen' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })

  test('6. Nach erneutem Öffnen ist das Schlüsselfeld leer und als gesetzt markiert', async ({
    authenticatedPage: page,
  }) => {
    await gotoModelManagement(page)
    await page.getByRole('button', { name: 'Neues Modell' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Anzeigename', { exact: false }).fill(KEY_MODEL_NAME)
    await dialog.getByLabel('Basis-Adresse', { exact: false }).fill(REACHABLE_BASE_URL)
    await dialog.getByLabel('Modell-Kennung', { exact: false }).fill(MODEL_IDENTIFIER)
    await dialog.getByLabel('API-Schlüssel', { exact: false }).fill('sk-e2e-test-schluessel')

    const [createResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' && response.url().endsWith('/api/v1/admin/models'),
      ),
      dialog.getByRole('button', { name: 'Anlegen' }).click(),
    ])
    const created = (await createResponse.json()) as LlmModelApiResponse
    keyModelId = created.id
    expect(created.apiKeySet).toBe(true)

    // "erneut öffnen" - a fresh navigation (not just a reload), the same helper every other test
    // here uses, gives the page a real, awaited round trip to the server rather than a client-side
    // reload directly on the heels of the just-completed create request.
    await gotoModelManagement(page)
    const card = modelCard(page, created.id)
    await expect(card).toBeVisible()
    await expandModelCard(page, created.id)

    await expect(card.getByLabel('API-Schlüssel', { exact: false })).toHaveValue('')
    await expect(
      card.getByRole('button', {
        name: `Gespeicherten Schlüssel von "${KEY_MODEL_NAME}" entfernen`,
      }),
    ).toBeVisible()
  })
})
