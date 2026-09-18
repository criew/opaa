import {
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type APIResponse,
  type Page,
} from '@playwright/test'
import { gotoLibraries, gotoLibraryDetail } from './chat'

/**
 * Bausteine der Fremdzugangs-Szenarien (test(e2e) #1723, Epic #1715): der Schalter der
 * Systemverwaltung, die Freigabe einer Bibliothek, die eigene Tokenverwaltung - und der MCP-Server,
 * der als einziger Prüfgegenstand dieser Suite kein Browser ist.
 *
 * Die drei Oberflächenhelfer fahren bewusst über die Oberfläche: Schalter, Freigabe und Ausstellung
 * sind die drei Entscheidungen, die der Kanal verlangt, und jede von ihnen ist eine Handlung eines
 * Menschen. Alles, was nur Vorbedingung oder Aufräumarbeit ist (Kennungen nachschlagen, eine
 * Befristung auf Sekunden verkürzen, am Ende zurücksetzen), läuft dagegen über die API - dieselbe
 * Arbeitsteilung wie in knowledge-library-nacharbeiten.spec.ts' Filler-Dokumenten.
 */

const FRONTEND_BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:3000'

/**
 * Der MCP-Endpunkt liegt am Backend und **nicht** hinter der nginx-Auslieferung des Frontends:
 * deren einzige Weiterleitung ist `/api/` (frontend/nginx.conf). Die Szenarien sprechen ihn deshalb
 * unter dem eigenen Host-Port des Stacks an, den scripts/run-e2e.mjs als E2E_BACKEND_BASE_URL
 * durchreicht - dieselbe Art Adresse wie E2E_AI_STUB_BASE_URL. Der Vorgabewert entspricht dem
 * Standard-Backend-Port dieses Ziels, damit `pnpm run test:playwright` gegen einen bereits
 * laufenden Stack ohne weitere Umgebung funktioniert.
 */
export const BACKEND_BASE_URL = process.env.E2E_BACKEND_BASE_URL ?? 'http://localhost:18081'

/** Die vier Werte, die eine Kanaleinstellung ausmacht, soweit die Szenarien sie brauchen. */
export interface ChannelSettings {
  enabled: boolean
  tokenMaxLifetimeDays: number
  tokenRateLimitPerHour: number
  allowedCidrs: string[]
  massRetrievalAlertThreshold: number
  serverInstructions: string
}

/**
 * Ein API-Kontext im Namen eines Dev-Nutzers. Der `dev`-Auth-Modus authentifiziert jede Anfrage
 * über diesen Kopf (siehe fixtures/auth.ts); ohne ihn liefe sie als Vorgabenutzer.
 */
export async function apiAs(devUser: string): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: FRONTEND_BASE_URL,
    extraHTTPHeaders: { 'X-OPAA-Dev-User': devUser },
  })
}

/**
 * Ein API-Kontext im Namen eines Zugangstokens - gegen das Backend, nicht gegen nginx: dieselbe
 * Adresse, unter der auch `/mcp` liegt, damit Lesewege und MCP im selben Kanal geprüft werden.
 * `token === null` ist der Aufruf ganz ohne Merkmal.
 */
export async function apiWithToken(token: string | null): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: BACKEND_BASE_URL,
    extraHTTPHeaders: token === null ? {} : { Authorization: `Bearer ${token}` },
  })
}

let nextRequestId = 1

/**
 * Ein JSON-RPC-Aufruf gegen `/mcp`. `Accept` nennt beide Medientypen, weil der Streamable-HTTP-
 * Transport alles andere mit 400 abweist; die Antwort selbst ist in der zustandsfreien Betriebsart
 * JSON. Gibt die rohe Antwort zurück - die Abweisungsszenarien prüfen den Status, nicht den Rumpf.
 */
export async function mcpRequest(
  context: APIRequestContext,
  method: string,
  params: Record<string, unknown> = {},
): Promise<APIResponse> {
  return context.post('/mcp', {
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
    },
    data: { jsonrpc: '2.0', id: nextRequestId++, method, params },
  })
}

/** Derselbe Aufruf, aber mit der Zusicherung, dass er beantwortet wurde, und als JSON-Objekt. */
export async function mcpCall(
  context: APIRequestContext,
  method: string,
  params: Record<string, unknown> = {},
): Promise<Record<string, unknown>> {
  const response = await mcpRequest(context, method, params)
  expect(response.status(), `${method} wurde nicht beantwortet: ${await response.text()}`).toBe(200)
  return (await response.json()) as Record<string, unknown>
}

/** Der Handschlag, mit dem jede Sitzung eines fremden Werkzeugs beginnt. */
export async function mcpInitialize(
  context: APIRequestContext,
): Promise<Record<string, unknown>> {
  const response = await mcpCall(context, 'initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'opaa-e2e', version: '1' },
  })
  return response.result as Record<string, unknown>
}

interface ToolDefinition {
  name: string
  description: string
}

/** Der Werkzeugkatalog dieses Tokens - Namen und Beschreibungen, wie das fremde Modell sie liest. */
export async function mcpTools(context: APIRequestContext): Promise<ToolDefinition[]> {
  const response = await mcpCall(context, 'tools/list')
  const result = response.result as { tools: ToolDefinition[] }
  return result.tools
}

/**
 * Ein Werkzeugaufruf mit der Zusicherung, dass er gelungen ist, und seinem strukturierten Ergebnis.
 * Eine fachliche Abweisung (erschöpftes Kontingent, fremde Trefferkennung) ist ein Ergebnis mit
 * `isError`, kein Transportfehler - deshalb wird sie hier geprüft und nicht am Status.
 */
export async function mcpTool(
  context: APIRequestContext,
  tool: string,
  args: Record<string, unknown> = {},
): Promise<Record<string, unknown>> {
  const response = await mcpCall(context, 'tools/call', { name: tool, arguments: args })
  const result = response.result as { isError?: boolean; structuredContent: Record<string, unknown> }
  expect(result.isError ?? false, `Werkzeug ${tool} antwortete mit einem Fehler`).toBe(false)
  return result.structuredContent
}

/** Die Namen der Bestände, die `list_libraries` für dieses Token nennt. */
export async function mcpLibraryNames(context: APIRequestContext): Promise<string[]> {
  const result = await mcpTool(context, 'list_libraries')
  return (result.libraries as Array<{ name: string }>).map((library) => library.name)
}

export interface McpHit {
  id: string
  library: string
  libraryId: string
  document: string
  text: string
}

/** Die Treffer eines `search`-Aufrufs, auf die Felder reduziert, die die Szenarien prüfen. */
export async function mcpSearch(
  context: APIRequestContext,
  query: string,
  args: Record<string, unknown> = {},
): Promise<McpHit[]> {
  const result = await mcpTool(context, 'search', { query, ...args })
  return result.results as McpHit[]
}

/** Der Schalter der Installation, über die Oberfläche der Systemverwaltung. */
export async function setChannelEnabled(adminPage: Page, enabled: boolean): Promise<void> {
  await adminPage.goto('/admin/external-access/channel')
  const toggle = adminPage.getByRole('switch', { name: 'Fremdzugänge erlauben' })
  await expect(toggle).toBeVisible()
  if ((await toggle.isChecked()) !== enabled) {
    await toggle.click()
  }
  await adminPage.getByRole('button', { name: 'Speichern' }).click()
  await expect(
    adminPage.getByText('Die Einstellungen wurden gespeichert und wirken ab dem nächsten Aufruf.'),
  ).toBeVisible()
}

/** Die Kanaleinstellungen, wie die Systemverwaltung sie sieht - für Sicherung und Wiederherstellung. */
export async function readChannelSettings(adminApi: APIRequestContext): Promise<ChannelSettings> {
  const response = await adminApi.get('/api/v1/system/external-access')
  expect(response.status()).toBe(200)
  const settings = (await response.json()) as ChannelSettings
  return settings
}

/** Setzt die Kanaleinstellungen wieder auf einen gesicherten Stand (nur im Aufräumen benutzt). */
export async function restoreChannelSettings(
  adminApi: APIRequestContext,
  settings: ChannelSettings,
): Promise<void> {
  await adminApi.put('/api/v1/system/external-access', {
    data: {
      enabled: settings.enabled,
      tokenMaxLifetimeDays: settings.tokenMaxLifetimeDays,
      tokenRateLimitPerHour: settings.tokenRateLimitPerHour,
      allowedCidrs: settings.allowedCidrs,
      massRetrievalAlertThreshold: settings.massRetrievalAlertThreshold,
      serverInstructions: settings.serverInstructions,
    },
  })
}

/** Die Kennung einer Bibliothek über ihren Namen - für die API-Schritte der Szenarien. */
export async function libraryIdByName(api: APIRequestContext, name: string): Promise<string> {
  const response = await api.get('/api/v1/libraries')
  expect(response.status()).toBe(200)
  const libraries = (await response.json()) as Array<{ id: string; name: string }>
  const match = libraries.find((library) => library.name === name)
  expect(match, `Bibliothek „${name}“ nicht in der eigenen Liste gefunden`).toBeTruthy()
  return match!.id
}

/**
 * Die Freigabe einer Bibliothek über die API. Nur dort, wo das Datum selbst Prüfgegenstand ist und
 * das Feld der Oberfläche es nicht ausdrücken kann - sie kennt nur ganze Tage, und Szenario 6
 * braucht eine Befristung, die im Lauf abläuft (das Issue nennt die Verwaltungsaktion ausdrücklich
 * als zulässigen Weg; auf Kalenderzeit zu warten ist keiner).
 */
export async function setLibraryReleaseViaApi(
  api: APIRequestContext,
  libraryId: string,
  enabled: boolean,
  expiresAt?: Date,
): Promise<void> {
  const response = await api.put(`/api/v1/libraries/${libraryId}/external-access`, {
    data: enabled ? { enabled: true, expiresAt: expiresAt!.toISOString() } : { enabled: false },
  })
  expect(response.status(), await response.text()).toBe(200)
}

/**
 * Öffnet den Zugriffsbereich einer Bibliothek (Detailseite → Verwaltung → Rechte verwalten), in dem
 * die Freigabe für Fremdzugänge steht.
 */
export async function openLibraryAccessDialog(page: Page, libraryName: string): Promise<void> {
  await gotoLibraries(page)
  await gotoLibraryDetail(page, libraryName)
  await page.getByRole('tab', { name: 'Verwaltung' }).click()
  await page.getByRole('button', { name: 'Rechte verwalten' }).click()
  await expect(page.getByRole('heading', { name: 'Fremdzugänge' })).toBeVisible()
}

/** Schließt den Zugriffsbereich wieder - im Dialog, weil auch eine Meldung „Schließen“ heißt (#784). */
export async function closeLibraryAccessDialog(page: Page): Promise<void> {
  await page.getByRole('dialog').getByRole('button', { name: 'Schließen' }).click()
}

/** Ein Datum im Format des Eingabefelds, so viele Tage in der Zukunft. */
export function dateInputValue(daysFromNow: number): string {
  const date = new Date()
  date.setDate(date.getDate() + daysFromNow)
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * Setzt oder nimmt die Freigabe einer Bibliothek über die Oberfläche zurück; der Zugriffsbereich
 * muss offen sein. Das Datum wird vor dem Schalter gefüllt, weil der Schalter es mitsendet.
 */
export async function setLibraryReleaseInDialog(
  page: Page,
  enabled: boolean,
  daysFromNow?: number,
): Promise<void> {
  if (enabled) {
    await page.getByLabel('Freigabe bis').fill(dateInputValue(daysFromNow ?? 30))
  }
  const toggle = page.getByRole('switch', { name: 'Über Fremdzugänge nutzbar' })
  if ((await toggle.isChecked()) !== enabled) {
    await toggle.click()
  }
  await expect(
    page.getByText(enabled ? /Freigegeben bis/ : /Die Freigabe wurde am .* zurückgenommen/),
  ).toBeVisible()
}

/** Die eigene Tokenverwaltung in den persönlichen Einstellungen. */
export async function gotoOwnTokens(page: Page): Promise<void> {
  await page.goto('/settings/tokens')
  await expect(page.getByRole('heading', { name: 'Zugangstokens' })).toBeVisible()
}

/**
 * Erzeugt ein Zugangstoken über die Oberfläche und gibt seinen Wert zurück - den einzigen Moment,
 * in dem es ihn gibt.
 */
export async function createToken(
  page: Page,
  name: string,
  libraryNames: string[],
): Promise<string> {
  await gotoOwnTokens(page)
  await page.getByRole('button', { name: 'Token erzeugen' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: 'Token erzeugen' })).toBeVisible()
  await dialog.getByLabel('Name / Zweck').fill(name)
  for (const libraryName of libraryNames) {
    await dialog.getByRole('checkbox', { name: libraryName }).check()
  }
  // exact: sonst träfe der Name auch die Schaltfläche „Token erzeugen“ hinter dem Dialog.
  await dialog.getByRole('button', { name: 'Erzeugen', exact: true }).click()

  const value = page.getByTestId('external-access-token-value')
  await expect(value).toBeVisible()
  const token = (await value.textContent()) ?? ''
  expect(token).toMatch(/^opaa_pat_/)
  await page.getByRole('button', { name: 'Kopiert, schließen' }).click()
  await expect(value).toHaveCount(0)
  return token
}

/** Die Zeile eines Tokens in einer der beiden Listen, über seinen Namen. */
export function tokenRow(page: Page, tokenName: string) {
  return page.getByRole('row').filter({ hasText: tokenName })
}
