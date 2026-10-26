import { expect, test } from '../fixtures/auth'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * Der geführte Wechsel des Embedding-Modells über den vollen Stack (#3214, ADR-0061): Eintragen,
 * Probeeinbettung, Vorschau, Bestätigung, Neueinbettung bis zum Umschalten, und der Wechsel nur
 * des Endpunkts ohne Neueinbettung.
 *
 * ai-stub bettet jede Eingabe gleich ein, unabhängig vom Modellnamen (e2e/ai-stub/server.mjs). Ein
 * anderer Modellname ist für OPAA deshalb ein Modellwechsel mit Neueinbettung, derselbe Name an
 * einer anderen Schreibweise der Adresse ein Endpunktwechsel; die Antworten der übrigen Szenarien
 * bleiben gleich. `afterAll` kehrt trotzdem zum ursprünglichen Modellnamen zurück, über dieselbe
 * Schnittstelle, damit kein späteres Szenario auf einem von diesem Lauf hinterlassenen Modell sitzt.
 */

const DEV_USER_HEADER = 'X-OPAA-Dev-User'
const RUN_TIMEOUT_MS = 120_000

interface EmbeddingInfo {
  model: string
  baseUrl?: string | null
  dimensions: number
  index?: { stale: boolean; running: boolean } | null
}

async function embeddingInfo(request: APIRequestContext): Promise<EmbeddingInfo> {
  const response = await request.get('/api/v1/admin/models/embedding-info', {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  expect(response.ok()).toBe(true)
  return (await response.json()) as EmbeddingInfo
}

async function awaitTheSwitch(request: APIRequestContext, model: string): Promise<void> {
  await expect
    .poll(
      async () => {
        const info = await embeddingInfo(request)
        return info.model === model && info.index?.stale === false && !info.index.running
      },
      { timeout: RUN_TIMEOUT_MS, intervals: [1_000] },
    )
    .toBe(true)
}

async function gotoEmbedding(page: Page): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().endsWith('/api/v1/admin/models/embedding-info'),
    ),
    page.goto('/admin/models/embedding'),
  ])
  await expect(page.getByRole('table', { name: 'Einbettungsmodell' })).toBeVisible()
}

test.describe.serial('Wechsel des Embedding-Modells (#3214)', () => {
  let original: EmbeddingInfo | undefined

  test.beforeAll(async ({ request }) => {
    original = await embeddingInfo(request)
  })

  test.afterAll(async ({ request }) => {
    if (!original) return
    const current = await embeddingInfo(request)
    if (current.model === original.model) return
    const headers = { [DEV_USER_HEADER]: 'dev-admin' }
    const draft = { baseUrl: current.baseUrl, model: original.model }
    const probe = await request.post('/api/v1/admin/models/embedding/probe', {
      headers,
      data: draft,
    })
    const probed = (await probe.json()) as { dimensions: number }
    const change = await request.post('/api/v1/admin/models/embedding/change', {
      headers,
      data: { ...draft, expectedDimensions: probed.dimensions },
    })
    expect(change.ok()).toBe(true)
    await awaitTheSwitch(request, original.model)
  })

  test('1. Ein anderes Modell wechselt mit Vorschau, Bestätigung und Neueinbettung', async ({
    authenticatedPage: page,
  }) => {
    const target = `${original?.model ?? 'bge-m3'}-wechsel-${Date.now()}`
    await gotoEmbedding(page)

    const model = page.getByLabel('Modell-Kennung', { exact: false })
    await model.fill(target)
    await page.getByRole('button', { name: 'Probeeinbettung' }).click()

    const preview = page.getByLabel('Vorschau des Wechsels')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('Abschnitte')
    await expect(page.getByText(/Vektorsuche ausgesetzt/).first()).toBeVisible()

    await page.getByRole('button', { name: 'Wechsel starten' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toContainText('bisherigen Vektoren weg')
    const [started] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith('/api/v1/admin/models/embedding/change'),
      ),
      dialog.getByRole('button', { name: 'Wechsel starten' }).click(),
    ])
    expect(started.ok()).toBe(true)

    await awaitTheSwitch(page.request, target)
    await gotoEmbedding(page)
    await expect(page.getByRole('table', { name: 'Einbettungsmodell' })).toContainText(target)
  })

  test('2. Derselbe Modellname an anderer Adresse ist ein Endpunktwechsel ohne Neueinbettung', async ({
    authenticatedPage: page,
  }) => {
    const before = await embeddingInfo(page.request)
    await gotoEmbedding(page)

    // Another spelling of the same endpoint: the stored key and the address check stay as they are.
    const address = page.getByLabel('Basis-Adresse', { exact: false })
    await address.fill(`${(before.baseUrl ?? '').replace(/\/+$/, '')}/`)
    await page.getByRole('button', { name: 'Probeeinbettung' }).click()

    await expect(page.getByText(/ein Wechsel des Endpunkts genügt/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Wechsel starten' })).toHaveCount(0)
    const [changed] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'PUT' &&
          response.url().endsWith('/api/v1/admin/models/embedding/endpoint'),
      ),
      page.getByRole('button', { name: 'Endpunkt ändern' }).click(),
    ])
    expect(changed.ok()).toBe(true)

    const after = await embeddingInfo(page.request)
    expect(after.model).toBe(before.model)
    expect(after.index?.stale).toBe(false)
  })
})
