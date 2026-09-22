import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { APIRequestContext, Page } from '@playwright/test'
import { expect, test } from '../fixtures/auth'
import { createLibraryWithDocument, gotoLibraries, gotoLibraryDetail } from '../fixtures/chat'
import {
  apiAs,
  apiWithToken,
  closeLibraryAccessDialog,
  createToken,
  gotoOwnTokens,
  libraryIdByName,
  mcpInitialize,
  mcpLibraryNames,
  mcpRequest,
  mcpSearch,
  mcpTool,
  mcpTools,
  openLibraryAccessDialog,
  readChannelSettings,
  restoreChannelSettings,
  setChannelEnabled,
  setLibraryReleaseInDialog,
  setLibraryReleaseViaApi,
  tokenRow,
  type ChannelSettings,
} from '../fixtures/externalAccess'

/**
 * Fremdzugänge über den vollen Stack (test(e2e) #1723, Epic #1715,
 * docs/features/external-access.md): der Weg von der Freigabe über das Token bis zum Treffer - und
 * die Gegenprobe, jeden der vier Faktoren einzeln entzogen.
 *
 * Die effektive Sicht eines Tokens ist die Schnittmenge aus vier Dingen: Schalter der Installation,
 * Freigabe der Bibliothek, Auswahl im Token und Leserechten der Person. Die Szenarien unten nehmen
 * der Reihe nach jeden dieser vier weg und prüfen, dass die Treffer verschwinden, ohne dass etwas
 * anderes angefasst wurde. Genau dafür braucht es diese Suite: Jede Schicht für sich ist im Backend
 * geprüft (io.opaa.mcp.McpServerIntegrationTest), aber erst hier laufen Oberfläche, echte
 * Filterkette und der MCP-Server eines laufenden Stacks zusammen.
 *
 * **Zwei Adressen.** Was eine Person tut, läuft durch die nginx-Auslieferung des Frontends wie in
 * jedem anderen Szenario dieser Suite. Was ein Zugangstoken tut - `/mcp` und die drei REST-Lesewege
 * - geht dagegen **am Frontend vorbei an den Host-Port des Backends** (`E2E_BACKEND_BASE_URL`,
 * siehe fixtures/externalAccess.ts), damit beide Tokenwege über dieselbe Adresse gehen und ein
 * Unterschied zwischen ihnen nie vom Weg dorthin kommt. Dass die nginx-Auslieferung `/mcp`
 * ebenfalls an das Backend weiterreicht (#1721), prüft mcp-endpoint-routing.spec.ts.
 *
 * `test.describe.serial`, und zwar zwingend: Die Szenarien bauen eine Installation nacheinander
 * weiter (Kanal an, Bibliotheken, Freigaben, Tokens), und drei von ihnen sind irreversibel - eine
 * erloschene Auswahl im Token lebt nicht wieder auf, und ein widerrufenes Token kommt nicht zurück.
 *
 * Handelnde: `dev-format-pipelines` ist die Person, die Bibliotheken anlegt und Tokens erzeugt -
 * das Konto, das nie eine Chatfrage stellt (siehe README.md, „Vier Testnutzer"), weshalb seine
 * Uploads keinem zitatprüfenden Szenario in die Trefferliste geraten. `dev-admin` schaltet den
 * Kanal und hält Bibliothek C, die die Person nur über eine Freigabe liest - Szenario 12 (Leserecht
 * entzogen) braucht eine Bibliothek, die der Tokeninhaberin nicht selbst gehört.
 *
 * Aufgeräumt wird in `test.afterAll` über die API: Schalter, Freigaben und Tokens stehen danach
 * wieder im Ausgangszustand, und die drei angelegten Bibliotheken samt ihren Dokumenten sind fort -
 * anders als sonst in dieser Suite, weil die Fixture-Dokumente hier keinen Zweck mehr haben und der
 * Rechtefilter späterer Szenarien nichts von ihnen wissen muss.
 *
 * **Die Tokenwerte stehen im Klartext im Trace.** Playwright hält bei einem Fehlschlag Trace und
 * Anfrageköpfe fest, und dort steht `Authorization: Bearer opaa_pat_…` so, wie es über die Leitung
 * ging - verdecken ließe sich das nur, indem der Test seinen eigenen Prüfgegenstand verbirgt. Zwei
 * Dinge entschärfen das: Jedes Token dieses Laufs wird in `test.afterAll` widerrufen und wirkt
 * danach nicht mehr, und `scripts/run-e2e.mjs` fährt den Stack samt Datenbank mit `down -v` ab -
 * das Token existiert nicht einmal mehr als Zeile. Ein hochgeladenes Trace-Artefakt trägt also
 * einen Wert, der gegen nichts mehr gilt.
 */

const documentPath = (fileName: string) =>
  join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'demo', 'seed', 'e2e-data', 'test-documents', fileName)

const DOCUMENT_A = 'fremdzugang-bibliothek-a.txt'
const DOCUMENT_B = 'fremdzugang-bibliothek-b.txt'
const DOCUMENT_C = 'fremdzugang-bibliothek-c.txt'

// Eindeutig je Lauf, damit ein nicht abgebauter Stack (oder ein abgebrochener Vorlauf) nie eine
// gleichnamige Bibliothek oder ein gleichnamiges Token hinterlässt, das ein Szenario trifft.
const runId = Date.now()
const LIBRARY_A = `E2E Fremdzugang A ${runId}`
const LIBRARY_B = `E2E Fremdzugang B ${runId}`
const LIBRARY_C = `E2E Fremdzugang C ${runId}`

const TOKEN_WIDE = `E2E Claude Code ${runId}`
const TOKEN_NARROW = `E2E Cursor ${runId}`
const TOKEN_EXPIRY = `E2E Ablaufende Freigabe ${runId}`
const TOKEN_FRESH = `E2E Neues Token ${runId}`

const QUESTION = 'Welche Frist gilt für den Widerspruch?'

const PERSON = 'dev-format-pipelines'
const PERSON_DISPLAY_NAME = 'Dev Format Pipelines'

let libraryAId = ''
let libraryBId = ''
let libraryCId = ''
let wideToken = ''
let narrowToken = ''
let expiryToken = ''
let freshToken = ''
let originalChannelSettings: ChannelSettings | null = null

test.describe.serial('Fremdzugänge', () => {
  test.afterAll(async () => {
    const personApi = await apiAs(PERSON)
    const adminApi = await apiAs('dev-admin')
    try {
      await revokeOwnTokens(personApi)

      // Die Kennungen kommen aus den Listen, nicht aus den Modulvariablen: Bricht ein Lauf vor
      // oder in Szenario 2 ab, sind die Variablen leer, die Bibliotheken aber angelegt - das
      // Aufräumen muss gerade dann greifen. Die Namen tragen die Kennung des Laufs und treffen
      // deshalb nie eine Bibliothek eines anderen.
      const ids = {
        [LIBRARY_A]: await findLibraryId(personApi, LIBRARY_A),
        [LIBRARY_B]: await findLibraryId(personApi, LIBRARY_B),
        [LIBRARY_C]: await findLibraryId(adminApi, LIBRARY_C),
      }
      const apiOf = (name: string) => (name === LIBRARY_C ? adminApi : personApi)
      for (const [name, id] of Object.entries(ids)) {
        await withdrawRelease(apiOf(name), id)
      }
      // Vor dem Löschen, nicht danach: Eine gelöschte Bibliothek steht auf keiner Freigabeliste,
      // egal ob die Rücknahme je gelaufen ist - die Prüfung „nichts bleibt freigegeben" wäre sonst
      // von selbst wahr.
      await expectReleasesWithdrawn(adminApi, ids)

      for (const [name, id] of Object.entries(ids)) {
        await deleteLibrary(apiOf(name), id)
      }
      if (originalChannelSettings) {
        await restoreChannelSettings(adminApi, originalChannelSettings)
      }
      await expectNothingLeftBehind(personApi, adminApi, ids)
    } finally {
      await personApi.dispose()
      await adminApi.dispose()
    }
  })

  test('1. Bei geschlossenem Kanal gibt es den Dienst nicht und kein Token lässt sich anlegen', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    const adminApi = await apiAs('dev-admin')
    originalChannelSettings = await readChannelSettings(adminApi)
    await adminApi.dispose()

    await setChannelEnabled(adminPage, false)

    const anonymous = await apiWithToken(null)
    // Ohne Zugangsmerkmal erfährt ein Aufrufer bei geschlossenem Kanal nicht einmal, dass es den
    // Dienst gibt - 404, nicht 401 und nicht 503.
    expect((await mcpRequest(anonymous, 'tools/list')).status()).toBe(404)
    await anonymous.dispose()

    await gotoOwnTokens(personPage)
    await expect(personPage.getByRole('button', { name: 'Token erzeugen' })).toBeDisabled()
    await expect(
      personPage.getByText('Fremdzugänge sind für diese Installation abgeschaltet.', {
        exact: false,
      }),
    ).toBeVisible()
  })

  test('2. Kanal einschalten, Bibliotheken anlegen, A und C freigeben', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    // Drei Uploads samt Indizierung und zwei Freigaben über die Oberfläche - das Vielfache eines
    // gewöhnlichen Szenarios dieser Suite, und der Aufbau, auf dem alle folgenden stehen.
    test.setTimeout(240_000)

    await setChannelEnabled(adminPage, true)

    await createLibraryWithDocument(personPage, LIBRARY_A, documentPath(DOCUMENT_A), DOCUMENT_A)
    await createLibraryWithDocument(personPage, LIBRARY_B, documentPath(DOCUMENT_B), DOCUMENT_B)
    await createLibraryWithDocument(adminPage, LIBRARY_C, documentPath(DOCUMENT_C), DOCUMENT_C)

    await shareWithPerson(adminPage, LIBRARY_C)

    await openLibraryAccessDialog(adminPage, LIBRARY_C)
    await setLibraryReleaseInDialog(adminPage, true, 30)
    await closeLibraryAccessDialog(adminPage)

    await openLibraryAccessDialog(personPage, LIBRARY_A)
    await setLibraryReleaseInDialog(personPage, true, 30)
    // Die Zahl, die der Bibliotheksverantwortung als einziger Anhalt bleibt - eine Zahl, keine
    // Namen. Vor dem ersten Token ist sie null.
    await expect(personPage.getByText('Derzeit in 0 Zugangstokens enthalten.')).toBeVisible()
    await closeLibraryAccessDialog(personPage)

    const personApi = await apiAs(PERSON)
    const adminApi = await apiAs('dev-admin')
    libraryAId = await libraryIdByName(personApi, LIBRARY_A)
    libraryBId = await libraryIdByName(personApi, LIBRARY_B)
    libraryCId = await libraryIdByName(adminApi, LIBRARY_C)
    await personApi.dispose()
    await adminApi.dispose()
  })

  test('3. Die Auswahl umfasst nur freigegebene Bibliotheken, und ihr Leerzustand nennt die Ansprechstelle', async ({
    formatPipelinesPage: personPage,
    outsiderPage,
  }) => {
    await gotoOwnTokens(personPage)
    await personPage.getByRole('button', { name: 'Token erzeugen' }).click()
    const dialog = personPage.getByRole('dialog')
    await expect(dialog.getByRole('checkbox', { name: LIBRARY_A })).toBeVisible()
    await expect(dialog.getByRole('checkbox', { name: LIBRARY_C })).toBeVisible()
    // Lesbar, aber nicht freigegeben: B gehört derselben Person und erscheint trotzdem nicht.
    await expect(dialog.getByRole('checkbox', { name: LIBRARY_B })).toHaveCount(0)
    await expect(dialog.getByText('Freigabe bis', { exact: false }).first()).toBeVisible()
    await dialog.getByRole('button', { name: 'Abbrechen' }).click()

    // Eine Person ganz ohne freigegebene Bibliothek bekommt keinen leeren Kasten, sondern den Grund
    // und die Stelle, die ihn ändern kann.
    await gotoOwnTokens(outsiderPage)
    await outsiderPage.getByRole('button', { name: 'Token erzeugen' }).click()
    await expect(
      outsiderPage
        .getByRole('dialog')
        .getByText('Derzeit ist keine Bibliothek für Fremdzugänge wählbar', { exact: false }),
    ).toBeVisible()
    await expect(
      outsiderPage.getByRole('dialog').getByText('sprechen Sie diese Verwaltung an', {
        exact: false,
      }),
    ).toBeVisible()
  })

  test('4. Der Tokenwert ist genau einmal zu sehen, danach nirgends mehr', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    wideToken = await createToken(personPage, TOKEN_WIDE, [LIBRARY_A, LIBRARY_C])

    const row = tokenRow(personPage, TOKEN_WIDE)
    await expect(row).toContainText(LIBRARY_A)
    await expect(row).toContainText(LIBRARY_C)
    await expect(row).toContainText('gültig')
    await expect(personPage.locator('body')).not.toContainText(wideToken)

    await personPage.reload()
    await expect(tokenRow(personPage, TOKEN_WIDE)).toBeVisible()
    await expect(personPage.locator('body')).not.toContainText(wideToken)

    await adminPage.goto('/admin/external-access/tokens')
    await expect(tokenRow(adminPage, TOKEN_WIDE)).toContainText(PERSON_DISPLAY_NAME)
    await expect(adminPage.locator('body')).not.toContainText(wideToken)
  })

  test('5. MCP: Handschlag, Werkzeugkatalog und die drei Werkzeuge', async () => {
    const mcp = await apiWithToken(wideToken)

    const handshake = await mcpInitialize(mcp)
    expect(handshake.protocolVersion).toBe('2025-06-18')
    expect(String(handshake.instructions ?? '')).not.toHaveLength(0)

    const tools = await mcpTools(mcp)
    expect(tools.map((tool) => tool.name).sort()).toEqual(['fetch', 'list_libraries', 'search'])
    for (const tool of tools) {
      // Die Beschreibungen entstehen je Anfrage aus der effektiven Sicht dieses Tokens.
      expect(tool.description).toContain(LIBRARY_A)
      expect(tool.description).toContain(LIBRARY_C)
      expect(tool.description).not.toContain(LIBRARY_B)
    }

    expect((await mcpLibraryNames(mcp)).sort()).toEqual([LIBRARY_A, LIBRARY_C].sort())

    const hits = await mcpSearch(mcp, QUESTION, { maxHits: 10 })
    expect(hits.length).toBeGreaterThan(0)
    for (const hit of hits) {
      expect([libraryAId, libraryCId]).toContain(hit.libraryId)
    }
    const hitFromA = hits.find((hit) => hit.document === DOCUMENT_A)
    expect(hitFromA, 'kein Treffer aus der freigegebenen Bibliothek A').toBeTruthy()

    // Dieselbe Frage, auf die nicht freigegebene Bibliothek eingegrenzt: Der Rechtefilter sitzt in
    // der Suche, eine Nennung von außen weitet nichts.
    expect(await mcpSearch(mcp, QUESTION, { libraryIds: [libraryBId] })).toHaveLength(0)

    const passage = await mcpTool(mcp, 'fetch', { id: hitFromA!.id })
    expect(String(passage.text)).toContain('Widerspruchsfrist')
    expect(passage.whole).toBe(false)

    await mcp.dispose()
  })

  test('6. Ein zweites Token mit anderer Sicht bekommt andere Beschreibungen', async ({
    formatPipelinesPage: personPage,
  }) => {
    narrowToken = await createToken(personPage, TOKEN_NARROW, [LIBRARY_C])

    const mcp = await apiWithToken(narrowToken)
    for (const tool of await mcpTools(mcp)) {
      expect(tool.description).toContain(LIBRARY_C)
      expect(tool.description).not.toContain(LIBRARY_A)
    }
    expect(await mcpLibraryNames(mcp)).toEqual([LIBRARY_C])
    await mcp.dispose()
  })

  test('7. Ein Token erreicht die Lesewege und sonst nichts', async () => {
    const api = await apiWithToken(wideToken)

    expect((await api.get('/api/v1/search/libraries')).status()).toBe(200)
    expect((await api.post('/api/v1/search', { data: { question: QUESTION } })).status()).toBe(200)

    // Die Gegenprobe zu oben: Was nicht auf der Freigabeliste steht, wird abgewiesen - der
    // Bibliotheksendpunkt der Weboberfläche, die Generierung, die Indizierung und die Verwaltung.
    expect((await api.get('/api/v1/libraries')).status()).toBe(403)
    expect((await api.post('/api/v1/query', { data: { question: QUESTION } })).status()).toBe(403)
    expect((await api.post(`/api/v1/libraries/${libraryAId}/indexing`, { data: {} })).status()).toBe(
      403,
    )
    expect((await api.get('/api/v1/admin/external-access/tokens')).status()).toBe(403)
    expect((await api.get('/api/v1/external-access/tokens')).status()).toBe(403)

    await api.dispose()
  })

  test('8. Notaus: die offene Sitzung überdauert ihn nicht, das Wiedereinschalten braucht kein neues Token', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    // „Sitzung" heißt hier: derselbe Client, derselbe Handschlag, dieselbe Verbindung, dasselbe
    // Token. Eine Sitzung im Sinne eines serverseitigen Zustands gibt es nicht - der Server läuft
    // zustandsfrei (ADR-0035, Entscheidung 1), und genau deshalb kann sie den Notaus nicht
    // überdauern. Belegt wird damit die Auswertung je Aufruf: Derselbe Client, der eben noch
    // durchkam, wird im nächsten Aufruf abgewiesen und danach wieder bedient, ohne je ein Token
    // getauscht oder neu initialisiert zu haben.
    const mcp = await apiWithToken(wideToken)
    await mcpInitialize(mcp)
    expect(await mcpLibraryNames(mcp)).toHaveLength(2)

    await setChannelEnabled(adminPage, false)

    // Derselbe Client, dieselbe Sitzung, der nächste Werkzeugaufruf: abgewiesen, mit benannter
    // Ursache - „Kanal zu" muss von „falscher Pfad" unterscheidbar bleiben.
    const refused = await mcpRequest(mcp, 'tools/call', {
      name: 'list_libraries',
      arguments: {},
    })
    expect(refused.status()).toBe(503)
    const body = await refused.text()
    expect(body).toContain('channel_closed')
    expect(body).toContain('ausgeschaltet')

    const anonymous = await apiWithToken(null)
    expect((await mcpRequest(anonymous, 'tools/list')).status()).toBe(404)
    await anonymous.dispose()

    expect((await mcp.post('/api/v1/search', { data: { question: QUESTION } })).status()).toBe(401)

    await gotoOwnTokens(personPage)
    await expect(tokenRow(personPage, TOKEN_WIDE)).toContainText('wirkt derzeit nicht')

    await setChannelEnabled(adminPage, true)
    // Das Token wurde nicht angefasst und wirkt wieder - der Notaus vernichtet keine Einrichtung.
    expect(await mcpLibraryNames(mcp)).toHaveLength(2)
    await mcp.dispose()
  })

  test('9. Die eigene Sicht zeigt ein Datum, die Verwaltungssicht keines und keinen Personenfilter', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    await gotoOwnTokens(personPage)
    await expect(personPage.getByRole('columnheader', { name: 'Zuletzt benutzt' })).toBeVisible()
    // Die Zelle, nicht die Zeile: „Erstellt" trägt heute ebenfalls das heutige Datum, und die
    // Aussage ist die des Nutzungstags. Spalten der eigenen Liste: Name, Präfix, Bibliotheken,
    // Erstellt, Läuft ab, Zuletzt benutzt, Zustand.
    await expect(lastUsedCell(personPage, TOKEN_WIDE)).toHaveText(today())
    // Ein Datum, keine Häufigkeit: nirgends in der Zeile steht eine Zahl von Aufrufen.
    await expect(tokenRow(personPage, TOKEN_WIDE)).not.toContainText(/\d+\s*(mal|Aufrufe)/)

    await adminPage.goto('/admin/external-access/tokens')
    await expect(tokenRow(adminPage, TOKEN_WIDE)).toBeVisible()
    await expect(adminPage.getByRole('columnheader', { name: 'Zuletzt benutzt' })).toHaveCount(0)
    // Gefiltert wird über Zustand und Ablauf, nicht über Personen.
    await expect(adminPage.getByLabel('Zustand')).toBeVisible()
    await expect(
      adminPage.getByRole('combobox', { name: /Person|Besitzer|Nutzer/ }),
    ).toHaveCount(0)
    await expect(adminPage.getByRole('textbox', { name: /Person|Besitzer|Nutzer/ })).toHaveCount(0)
  })

  test('10. Zurückgenommene Freigabe: die Auswahl wird ausgesetzt und lebt nicht wieder auf', async ({
    formatPipelinesPage: personPage,
  }) => {
    await openLibraryAccessDialog(personPage, LIBRARY_A)
    await setLibraryReleaseInDialog(personPage, false)
    await closeLibraryAccessDialog(personPage)

    const mcp = await apiWithToken(wideToken)
    expect(await mcpLibraryNames(mcp)).toEqual([LIBRARY_C])
    expect(await mcpSearch(mcp, QUESTION, { libraryIds: [libraryAId] })).toHaveLength(0)

    await gotoOwnTokens(personPage)
    await expect(tokenRow(personPage, TOKEN_WIDE)).toContainText('Freigabe ausgesetzt')

    await openLibraryAccessDialog(personPage, LIBRARY_A)
    await setLibraryReleaseInDialog(personPage, true, 30)
    await closeLibraryAccessDialog(personPage)

    // Die erneute Freigabe belebt die erloschene Auswahl nicht - dafür ist ein neues Token nötig,
    // und genau das erreicht die Bibliothek wieder.
    expect(await mcpLibraryNames(mcp)).toEqual([LIBRARY_C])
    await mcp.dispose()

    freshToken = await createToken(personPage, TOKEN_FRESH, [LIBRARY_A])
    const fresh = await apiWithToken(freshToken)
    expect(await mcpLibraryNames(fresh)).toEqual([LIBRARY_A])
    await fresh.dispose()
  })

  test('11. Erloschene Freigabe: die Treffer verschwinden ohne Zutun', async ({
    formatPipelinesPage: personPage,
  }) => {
    const personApi = await apiAs(PERSON)
    await setLibraryReleaseViaApi(personApi, libraryBId, true, inDays(30))

    expiryToken = await createToken(personPage, TOKEN_EXPIRY, [LIBRARY_B])
    const mcp = await apiWithToken(expiryToken)
    expect(await mcpLibraryNames(mcp)).toEqual([LIBRARY_B])

    // Die Befristung wird auf wenige Sekunden verkürzt - eine Verwaltungsaktion, die den Ablauf
    // auslöst, statt auf Kalenderzeit zu warten (#1723, technischer Hinweis).
    await setLibraryReleaseViaApi(personApi, libraryBId, true, new Date(Date.now() + 2_000))
    await expect
      .poll(async () => (await mcpLibraryNames(mcp)).length, { timeout: 20_000 })
      .toBe(0)

    await setLibraryReleaseViaApi(personApi, libraryBId, true, inDays(30))
    expect(await mcpLibraryNames(mcp)).toHaveLength(0)

    await gotoOwnTokens(personPage)
    await expect(tokenRow(personPage, TOKEN_EXPIRY)).toContainText('Freigabe ausgesetzt')

    await mcp.dispose()
    await personApi.dispose()
  })

  test('12. Entzogenes Leserecht: die Treffer verschwinden, ohne dass Freigabe oder Token angefasst wurden', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    await gotoLibraries(adminPage)
    await gotoLibraryDetail(adminPage, LIBRARY_C)
    await adminPage.getByRole('tab', { name: 'Verwaltung' }).click()
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    await adminPage
      .getByRole('button', { name: `Freigabe für ${PERSON_DISPLAY_NAME} entziehen` })
      .click()
    const confirmRevoke = adminPage
      .getByRole('dialog')
      .filter({ has: adminPage.locator('#confirm-question') })
    await confirmRevoke.locator('#confirm-accept').click()
    await expect(confirmRevoke).toHaveCount(0)
    await closeLibraryAccessDialog(adminPage)

    const mcp = await apiWithToken(wideToken)
    expect(await mcpLibraryNames(mcp)).toHaveLength(0)
    expect(await mcpSearch(mcp, QUESTION)).toHaveLength(0)
    await mcp.dispose()

    // Das Token eines anderen Bestands ist unberührt - entzogen wurde ein Leserecht, nicht der
    // Kanal.
    const fresh = await apiWithToken(freshToken)
    expect(await mcpLibraryNames(fresh)).toEqual([LIBRARY_A])
    await fresh.dispose()

    // Die Freigabe von C steht unverändert, und die Auswahl im Token ist nicht ausgesetzt: Ein
    // fehlendes Leserecht erlischt nicht, es wirkt nur nicht.
    await adminPage.goto('/admin/library-releases')
    await expect(adminPage.getByText(LIBRARY_C, { exact: true })).toBeVisible()
    await gotoOwnTokens(personPage)
    await expect(tokenRow(personPage, TOKEN_WIDE).getByText('Freigabe ausgesetzt')).toHaveCount(1)
  })

  test('13. Widerruf durch die Person, Sperre durch die Systemverwaltung', async ({
    authenticatedPage: adminPage,
    formatPipelinesPage: personPage,
  }) => {
    await gotoOwnTokens(personPage)
    await personPage.getByRole('button', { name: `Token „${TOKEN_FRESH}“ widerrufen` }).click()
    const confirmRevoke = personPage
      .getByRole('dialog')
      .filter({ has: personPage.locator('#confirm-question') })
    await confirmRevoke.locator('#confirm-accept').click()
    await expect(confirmRevoke).toHaveCount(0)
    await expect(tokenRow(personPage, TOKEN_FRESH)).toContainText('widerrufen')

    const revoked = await apiWithToken(freshToken)
    expect((await mcpRequest(revoked, 'tools/list')).status()).toBe(401)
    await revoked.dispose()

    await adminPage.goto('/admin/external-access/tokens')
    await adminPage
      .getByRole('button', {
        name: `Aktionen für das Token „${TOKEN_WIDE}“ von ${PERSON_DISPLAY_NAME}`,
      })
      .click()
    await adminPage.getByRole('menuitem', { name: 'Token sperren' }).click()
    const confirmBlock = adminPage
      .getByRole('dialog')
      .filter({ has: adminPage.locator('#confirm-question') })
    await confirmBlock.locator('#confirm-accept').click()
    await expect(confirmBlock).toHaveCount(0)
    await expect(tokenRow(adminPage, TOKEN_WIDE)).toContainText('gesperrt')

    const blocked = await apiWithToken(wideToken)
    expect((await mcpRequest(blocked, 'tools/list')).status()).toBe(401)
    await blocked.dispose()

    // „Zuletzt benutzt" wird mit dem Widerruf gelöscht - es beantwortete „darf ich das widerrufen?".
    await gotoOwnTokens(personPage)
    await expect(lastUsedCell(personPage, TOKEN_FRESH)).toHaveText('—')
  })
})

/**
 * Die Zelle „Zuletzt benutzt" einer Zeile der eigenen Tokenliste. Über den Index, weil die Zelle
 * keine eigene Beschriftung trägt und die Zeile daneben zwei weitere Datumsangaben führt
 * (OwnExternalAccessTokensSection.tsx: Name, Präfix, Bibliotheken, Erstellt, Läuft ab, Zuletzt
 * benutzt, Zustand, Aktionen).
 */
function lastUsedCell(page: Page, tokenName: string) {
  return tokenRow(page, tokenName).getByRole('cell').nth(5)
}

function today(): string {
  return new Date().toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

function inDays(days: number): Date {
  return new Date(Date.now() + days * 86_400_000)
}

/** Teilt LIBRARY_C mit der Person - dieselbe Strecke wie in knowledge-libraries.spec.ts. */
async function shareWithPerson(adminPage: Page, libraryName: string) {
  await gotoLibraries(adminPage)
  await gotoLibraryDetail(adminPage, libraryName)
  await adminPage.getByRole('tab', { name: 'Verwaltung' }).click()
  await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
  await adminPage.getByRole('button', { name: 'Freigeben' }).click()
  const personInput = adminPage.getByRole('combobox', { name: 'Person suchen' })
  await personInput.click()
  await personInput.fill('Format')
  await adminPage.getByRole('option', { name: new RegExp(PERSON_DISPLAY_NAME) }).click()
  await adminPage.getByRole('button', { name: 'Freigeben' }).last().click()
  await expect(adminPage.getByText(PERSON_DISPLAY_NAME).first()).toBeVisible()
  await closeLibraryAccessDialog(adminPage)
}

/**
 * Das Aufräumen ist hier selbst Prüfgegenstand (#1723): Schalter, Freigaben und Tokens stehen
 * danach im Ausgangszustand, und die angelegten Bibliotheken sind fort. Bliebe eine Freigabe oder
 * ein wirksames Token stehen, hinterließe dieser Lauf einer späteren Suite eine geöffnete Tür.
 */
async function expectNothingLeftBehind(
  personApi: APIRequestContext,
  adminApi: APIRequestContext,
  ids: Record<string, string>,
): Promise<void> {
  if (originalChannelSettings) {
    expect((await readChannelSettings(adminApi)).enabled).toBe(originalChannelSettings.enabled)
  }
  const tokens = (await (await personApi.get('/api/v1/external-access/tokens')).json()) as {
    tokens: Array<{ name: string; status: string }>
  }
  expect(
    tokens.tokens.filter((token) => token.status === 'ACTIVE').map((token) => token.name),
  ).toEqual([])

  const libraries = (await (await personApi.get('/api/v1/libraries')).json()) as Array<{
    name: string
  }>
  const names = libraries.map((library) => library.name)
  for (const name of Object.keys(ids)) {
    expect(names, `Bibliothek „${name}“ ist nach dem Lauf noch da`).not.toContain(name)
  }
}

/**
 * Die Rücknahme der drei Freigaben, belegt **bevor** gelöscht wird: für jede Bibliothek der Zustand
 * ihres Reichweitenfelds (alles außer `ACTIVE` - je nachdem, wie weit ein abgebrochener Lauf kam,
 * `WITHDRAWN` oder `NEVER_SET`) und zusätzlich, dass keine von ihnen mehr auf der Freigabeliste der
 * Systemverwaltung steht.
 */
async function expectReleasesWithdrawn(
  adminApi: APIRequestContext,
  ids: Record<string, string>,
): Promise<void> {
  for (const [name, id] of Object.entries(ids)) {
    if (!id) continue
    const library = (await (await adminApi.get(`/api/v1/libraries/${id}`)).json()) as {
      externalAccess?: { state: string }
    }
    expect(
      library.externalAccess?.state,
      `Die Freigabe von „${name}“ wurde nicht zurückgenommen`,
    ).not.toBe('ACTIVE')
  }

  const released = (await (
    await adminApi.get('/api/v1/admin/external-access/libraries')
  ).json()) as Array<{ libraryName: string }>
  const releasedNames = released.map((entry) => entry.libraryName)
  for (const name of Object.keys(ids)) {
    expect(releasedNames, `„${name}“ steht noch auf der Freigabeliste`).not.toContain(name)
  }
}

/** Die Kennung einer Bibliothek über ihren Namen, oder `''`, wenn es sie (nicht mehr) gibt. */
async function findLibraryId(api: APIRequestContext, name: string): Promise<string> {
  const response = await api.get('/api/v1/libraries')
  if (response.status() !== 200) return ''
  const libraries = (await response.json()) as Array<{ id: string; name: string }>
  return libraries.find((library) => library.name === name)?.id ?? ''
}

async function revokeOwnTokens(personApi: APIRequestContext): Promise<void> {
  const response = await personApi.get('/api/v1/external-access/tokens')
  if (response.status() !== 200) return
  const body = (await response.json()) as { tokens: Array<{ id: string; status: string }> }
  for (const token of body.tokens) {
    if (token.status === 'ACTIVE') {
      await personApi.delete(`/api/v1/external-access/tokens/${token.id}`)
    }
  }
}

async function withdrawRelease(api: APIRequestContext, libraryId: string): Promise<void> {
  if (!libraryId) return
  await api.put(`/api/v1/libraries/${libraryId}/external-access`, { data: { enabled: false } })
}

/** Erst die Dokumente, dann die Bibliothek: eine nicht leere UPLOAD-Bibliothek wird sonst abgelehnt. */
async function deleteLibrary(api: APIRequestContext, libraryId: string): Promise<void> {
  if (!libraryId) return
  const response = await api.get(`/api/v1/libraries/${libraryId}/documents?size=100`)
  if (response.status() === 200) {
    const page = (await response.json()) as { items: Array<{ id: string }> }
    for (const document of page.items) {
      await api.delete(`/api/v1/libraries/${libraryId}/documents/${document.id}`)
    }
  }
  await api.delete(`/api/v1/libraries/${libraryId}`)
}
