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
 * Zwei `describe`-Blöcke: Szenarien 1/2/4/6 teilen sich das eine im Compose-Stack seit dem
 * Seed-Lauf aktive Chat-Modell (io.opaa.llm.LlmModelSeedRunner übernimmt beim Erststart die
 * OPAA_OPENAI_*-Konfiguration aus e2e.env, die auf ai-stub zeigt - siehe #766/#767) und wechseln es
 * für die Dauer dieser Datei bewusst um, deshalb `test.describe.serial` mit erzwungener Reihenfolge
 * und geteiltem Zustand (`connectionTestModelId` u. a.). Szenarien 3 und 5 hängen an keinem
 * Zustand aus Szenario 1 - ein eigenes, nicht-serielles zweites `describe` verhindert, dass sie ihr
 * Signal verlieren, sobald Szenario 1 fehlschlägt (PR #770 review, Befund 7).
 *
 * `test.afterAll` aktiviert danach exakt das ursprünglich aktive Modell wieder (nicht bloß
 * irgendein funktionierendes) - genau die im Issue geforderte Rückaktivierung, ohne die jeder
 * spätere Chat-Test in dieser Suite (z. B. space-chats.spec.ts) auf einem von diesem Lauf
 * hinterlassenen Modell säße.
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

// docker-compose.e2e.yml veröffentlicht ai-stubs Port zusätzlich auf dem Host (#760, PR-Review
// Befund 2) - nötig, weil Szenario 2 GET /last-chat-model direkt vom Playwright/Node-Prozess aus
// abfragt, nicht über den Browser. run-e2e.mjs reicht die tatsächlich verwendete Portnummer über
// E2E_AI_STUB_BASE_URL durch; der Fallback hier greift bei `pnpm run test:playwright` gegen einen
// bereits laufenden Stack mit dem Compose-Default (18089).
const AI_STUB_BASE_URL = process.env.E2E_AI_STUB_BASE_URL ?? 'http://localhost:18089'

// Unique per run, wie in den übrigen Specs (siehe z. B. knowledge-libraries.spec.ts), damit ein
// erneuter lokaler Lauf gegen einen nicht abgebauten Stack nicht mit Modellen einer vorigen
// Ausführung kollidiert.
const runId = Date.now()
const CONNECTION_TEST_MODEL_NAME = `E2E Verbindungstest-Modell ${runId}`
const KEY_MODEL_NAME = `E2E Modell mit Schluessel ${runId}`
const TEST_API_KEY = 'sk-e2e-test-schluessel'

// ai-stub beantwortet jeden Pfad, der auf "/chat/completions" endet, unabhängig vom "model"-Feld
// im Request-Body (siehe e2e/ai-stub/server.mjs) - die Modell-Kennung ist hier daher frei wählbar,
// wird aber unten (Szenario 2) genutzt, um zu belegen, dass eine Chat-Anfrage tatsächlich bei
// *diesem* Modell ankommt.
const REACHABLE_BASE_URL = 'http://ai-stub:8089/v1'
const MODEL_IDENTIFIER = 'e2e-stub-chat'

// ".invalid" ist laut RFC 2606 reserviert und löst nie auf - deterministisch fehlschlagend, ganz
// ohne auf tatsächliche Netzwerk-/DNS-Gegebenheiten der CI-Umgebung angewiesen zu sein.
const UNREACHABLE_BASE_URL = 'http://opaa-e2e-unreachable.invalid:9999/v1'

// Die von io.opaa.llm.LlmModelConnectionTester#translateConnectionError tatsächlich erzeugten
// deutschen Meldungen - welche davon eine ".invalid"-Adresse im Compose-Netzwerk auslöst, hängt
// vom lokalen DNS-Resolver ab (beobachtet: sowohl "nicht gefunden" als auch "abgelehnt" in
// unterschiedlichen Läufen), daher die Alternation statt eines einzelnen erwarteten Wortlauts.
const CONNECTION_ERROR_PATTERN = /nicht gefunden|abgelehnt|Zeitlimit|nicht erreichbar|Zertifikat/i

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
  // Motion is not under test here. With every CSS transition/animation off, row menus, the form
  // dialog and the popup notifications reach their final state at once, which takes a whole class
  // of animation-timing flakiness out of this flow without touching any behaviour under test.
  await page.addStyleTag({
    content: '*, *::before, *::after { transition: none !important; animation: none !important; }',
  })
}

/**
 * Die Tabellenzeile eines Modells, erkannt am Anzeigenamen in ihrer ersten Spalte. Der zugängliche
 * Name einer Zeile ist die Verkettung aller ihrer Zellen, deshalb ein regulärer Ausdruck
 * (Teiltreffer) statt eines Namens - und deshalb genügt der je Lauf eindeutige Anzeigename, um
 * genau eine Zeile zu treffen.
 */
function modelRow(page: Page, displayName: string): Locator {
  return page.getByRole('row', { name: new RegExp(displayName) })
}

/**
 * Öffnet das Zeilenmenü eines Modells und liefert das Menü selbst - es hängt in einem Portal
 * außerhalb der Zeile und ist über sie nicht erreichbar. Schaltfläche und Menü tragen denselben
 * Namen, der das Modell nennt; beide sind damit eindeutig einem Modell zugeordnet.
 */
async function openRowMenu(page: Page, displayName: string): Promise<Locator> {
  await modelRow(page, displayName)
    .getByRole('button', { name: `Aktionen für „${displayName}“` })
    .click()
  const menu = page.getByRole('menu', { name: `Aktionen für „${displayName}“` })
  await expect(menu).toBeVisible()
  return menu
}

/**
 * Öffnet über „Bearbeiten“ des Zeilenmenüs den Formular-Dialog - und sichert dabei zu, dass er
 * das Modell trägt, dessen Zeile ihn geöffnet hat.
 */
async function openEditDialog(page: Page, displayName: string): Promise<Locator> {
  const menu = await openRowMenu(page, displayName)
  await menu.getByRole('menuitem', { name: 'Bearbeiten' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText(`„${displayName}“ bearbeiten`)).toBeVisible()
  return dialog
}

test.describe.serial('Modellverwaltung (#760)', () => {
  let originalActiveModelId: string | undefined
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
    // Guard (PR #770 review, Befund 3): beforeAll wirft, wenn die Seed-Voraussetzung verletzt ist
    // (originalActiveModelId bleibt dann undefined) - ohne diesen Guard würde afterAll trotzdem
    // ein POST .../undefined/activate absetzen, statt den eigentlichen Fehler sichtbar zu lassen.
    if (originalActiveModelId) {
      const reactivateResponse = await request.post(
        `/api/v1/admin/models/${originalActiveModelId}/activate`,
        { headers: { [DEV_USER_HEADER]: 'dev-admin' } },
      )
      expect(reactivateResponse.ok()).toBe(true)
    }
    for (const modelId of [connectionTestModelId, keyModelId]) {
      if (modelId) {
        const deleteResponse = await request.delete(`/api/v1/admin/models/${modelId}`, {
          headers: { [DEV_USER_HEADER]: 'dev-admin' },
        })
        expect(deleteResponse.ok()).toBe(true)
      }
    }
    // Verifiziert das Ergebnis, nicht nur dass die Aufrufe selbst erfolgreich waren (PR #770
    // review, Befund 3): genau originalActiveModelId ist danach wieder das einzige aktive Modell.
    if (originalActiveModelId) {
      const verifyResponse = await request.get('/api/v1/admin/models', {
        headers: { [DEV_USER_HEADER]: 'dev-admin' },
      })
      expect(verifyResponse.ok()).toBe(true)
      const models = (await verifyResponse.json()) as LlmModelApiResponse[]
      const active = models.filter((model) => model.active)
      expect(active).toHaveLength(1)
      expect(active[0]?.id).toBe(originalActiveModelId)
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

    const row = modelRow(page, CONNECTION_TEST_MODEL_NAME)
    await expect(row).toBeVisible()
    const menu = await openRowMenu(page, CONNECTION_TEST_MODEL_NAME)
    await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith(`/api/v1/admin/models/${created.id}/activate`),
      ),
      menu.getByRole('menuitem', { name: 'Aktiv setzen' }).click(),
    ])

    // Der Zustand steht als Wort in der Zeile - „Aktiv“ statt „Nicht aktiv“, die Farbe trägt
    // nie allein.
    await expect(row.getByText('Aktiv', { exact: true })).toBeVisible()
    // Und „Aktiv setzen“ verschwindet ausschließlich für das jetzt aktive Modell (ModelRowMenu
    // führt den Eintrag nur, solange !model.active).
    const menuAfterActivation = await openRowMenu(page, CONNECTION_TEST_MODEL_NAME)
    await expect(menuAfterActivation.getByRole('menuitem', { name: 'Aktiv setzen' })).toHaveCount(0)

    const models = await fetchModels(page)
    const active = models.filter((model) => model.active)
    expect(active).toHaveLength(1)
    expect(active[0]?.id).toBe(created.id)
  })

  test('2. Eine Chat-Anfrage beantwortet nach der Aktivierung ohne Neustart, vom neuen Modell', async ({
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

    // PR #770 review, Befund 2: Seed-Modell und das in Szenario 1 aktivierte Modell zeigen beide
    // auf denselben ai-stub, der jede Anfrage identisch beantwortet - "eine Antwort kam an" allein
    // würde also auch dann grün bleiben, wenn #758s Cache-Invalidierung ausbliebe und die Anfrage
    // tatsächlich noch beim alten, zwischengespeicherten Client landete. ai-stub merkt sich das
    // zuletzt empfangene "model"-Feld (server.mjs) und liefert es über GET /last-chat-model - hier
    // geprüft gegen genau die Modell-Kennung des in Szenario 1 aktivierten Modells.
    const lastModelResponse = await page.request.get(`${AI_STUB_BASE_URL}/last-chat-model`)
    expect(lastModelResponse.ok()).toBe(true)
    const { model: lastChatModel } = (await lastModelResponse.json()) as { model: string | null }
    expect(lastChatModel).toBe(MODEL_IDENTIFIER)
  })

  test('4. Löschen des aktiven Modells ist gesperrt', async ({ authenticatedPage: page }) => {
    await gotoModelManagement(page)
    const menu = await openRowMenu(page, CONNECTION_TEST_MODEL_NAME)

    const deleteItem = menu.getByRole('menuitem', { name: 'Löschen' })
    await expect(deleteItem).toHaveAttribute('aria-disabled', 'true')
    // Der Grund steht im Menü und ist über `aria-describedby` mit dem gesperrten Eintrag
    // verbunden, wird also auch vorgelesen - ein Tooltip fände nur eine Maus.
    const reason = menu
      .locator('li')
      .filter({ hasText: /aktive Modell kann nicht gelöscht werden/i })
    await expect(reason).toBeVisible()
    const reasonId = await reason.getAttribute('id')
    expect(reasonId).toBeTruthy()
    await expect(deleteItem).toHaveAttribute('aria-describedby', reasonId!)

    // Nicht nur die UI-Sperre - auch serverseitig abgelehnt (409), falls die Anfrage die
    // client-seitige Sperre umginge.
    const response = await page.request.delete(`/api/v1/admin/models/${connectionTestModelId}`, {
      headers: { [DEV_USER_HEADER]: 'dev-admin' },
    })
    expect(response.status()).toBe(409)
  })

  test('6. Nach erneutem Öffnen ist das Schlüsselfeld leer und der Schlüssel kommt nie zurück', async ({
    authenticatedPage: page,
  }) => {
    await gotoModelManagement(page)
    await page.getByRole('button', { name: 'Neues Modell' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Anzeigename', { exact: false }).fill(KEY_MODEL_NAME)
    await dialog.getByLabel('Basis-Adresse', { exact: false }).fill(REACHABLE_BASE_URL)
    await dialog.getByLabel('Modell-Kennung', { exact: false }).fill(MODEL_IDENTIFIER)
    await dialog.getByLabel('API-Schlüssel', { exact: false }).fill(TEST_API_KEY)

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

    // PR #770 review, Befund 1: `apiKeyInput` im Frontend ist ein reines `useState('')`, das nie
    // aus dem Modell befüllt wird - eine reine UI-Prüfung auf ein leeres Feld wäre auch dann grün,
    // wenn das Backend den Klartext-Schlüssel im JSON mitschickte und das Frontend ihn nur nicht
    // anzeigte. Die eigentliche Zusicherung ("kommt nie zurück") lässt sich nur gegen die
    // Rohantwort der API prüfen, nicht gegen die Oberfläche.
    const rawModels = await fetchModels(page)
    const rawJson = JSON.stringify(rawModels)
    expect(rawJson).not.toContain(TEST_API_KEY)
    expect(rawJson).not.toMatch(/"apiKey(Ciphertext)?"\s*:/)

    // "erneut öffnen" - a fresh navigation (not just a reload), the same helper every other test
    // here uses, gives the page a real, awaited round trip to the server rather than a client-side
    // reload directly on the heels of the just-completed create request.
    await gotoModelManagement(page)
    // Die Zeile sagt, *dass* ein Schlüssel hinterlegt ist - nie welcher.
    await expect(modelRow(page, KEY_MODEL_NAME).getByText('Schlüssel hinterlegt')).toBeVisible()

    const editDialog = await openEditDialog(page, KEY_MODEL_NAME)
    await expect(editDialog.getByLabel('API-Schlüssel', { exact: false })).toHaveValue('')
    await expect(
      editDialog.getByRole('button', {
        name: `Gespeicherten Schlüssel von "${KEY_MODEL_NAME}" entfernen`,
      }),
    ).toBeVisible()
  })
})

// PR #770 review, Befund 7: 3 und 5 hängen an keinem Zustand aus dem `describe.serial`-Block oben
// (kein erstelltes/aktiviertes Modell nötig) - ein eigener, nicht-serieller Block verhindert, dass
// beide ihr eigenes Signal verlieren, sobald Szenario 1 dort fehlschlägt und die restlichen
// Szenarien des seriellen Blocks übersprungen werden.
test.describe('Modellverwaltung (#760) - unabhängige Szenarien', () => {
  test('3. Ein Nutzer ohne SYSTEM_ADMIN sieht die Modellverwaltung nicht', async ({
    regularUserPage: page,
  }) => {
    // Positiver Anker zuerst (PR #770 review, Befund 4a): ohne ihn wäre `toHaveCount(0)` auf den
    // "Admin"-Link auch dann grün, wenn die globale Leiste selbst noch gar nicht gerendert hätte.
    // Seit #786 führen die Admin-Seiten über den "Admin"-Eintrag der globalen Leiste; die
    // einzelnen Links (Modelle, Gruppen, Branding) stehen nicht mehr in der Seitenleiste.
    await expect(page.getByRole('link', { name: 'Katalog' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Admin' })).toHaveCount(0)

    await page.goto('/admin/models')
    await expect(page.getByText(/nicht freigegeben/i)).toBeVisible()
    // #805 (Review zu #803): "Nicht-Admins sehen die Spalte nicht" (#800) war unzugesichert -
    // die Seite ist hier gerendert (positiver Anker eine Zeile darüber), die Admin-Landmarke
    // darf trotzdem nicht existieren.
    await expect(page.getByRole('navigation', { name: 'Administration' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Neues Modell' })).toHaveCount(0)

    // Nicht nur die UI-Sperre - auch die serverseitige Schranke (PR #770 review, Befund 4b).
    const response = await page.request.get('/api/v1/admin/models', {
      headers: { [DEV_USER_HEADER]: 'dev-user' },
    })
    expect(response.status()).toBe(403)
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
    // Ablehnung (ECONNREFUSED) statt auf "Host nicht gefunden" hinauslaufen - beobachtet in
    // unterschiedlichen lokalen Läufen, daher die Alternation aus den tatsächlich möglichen
    // deutschen Meldungen (translateConnectionError) statt eines einzelnen Wortlauts. Ein
    // großzügigeres Timeout als das Suite-Standard-`expect` (10s): die DNS-Auflösung selbst ist
    // nicht vom 5s-Connect-Timeout des Backends gedeckt und kann je nach Resolver länger dauern.
    await expect(dialog.getByRole('alert')).toHaveText(CONNECTION_ERROR_PATTERN, {
      timeout: 20_000,
    })

    // Anlegen bleibt trotzdem möglich - der fehlgeschlagene Test allein blockiert nichts. Hier aber
    // bewusst abgebrochen, damit dieses Szenario kein weiteres, aufzuräumendes Modell hinterlässt.
    await dialog.getByRole('button', { name: 'Abbrechen' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })
})
