import { expect, test } from '../fixtures/auth'
import { startFreshChat } from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'

/**
 * Live dictation (ADR-0070) through the whole stack: browser, nginx's WebSocket upgrade, the
 * backend's /api/v1/transcriptions/stream and ai-stub as a vLLM live service. Chromium's fake
 * microphone plays a tone; ai-stub answers audio frames with one word each and the final text on the
 * commit. In the second scenario ai-stub drops the connection at the first audio frame, and the
 * recording made beside it goes to the batch endpoint instead.
 *
 * The transcription role is installation-wide state: switched on with live before the first
 * scenario, off again after the last.
 */

const AI_STUB_BASE_URL = process.env.E2E_AI_STUB_BASE_URL ?? 'http://localhost:18089'
const LIVE_TRANSCRIPT = 'Welche Frist gilt live?'
const BATCH_TRANSCRIPT = 'Welche Frist gilt aus der Aufnahme?'

test.use({
  launchOptions: {
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  },
  permissions: ['microphone'],
})

async function setLiveDictation(enabled: boolean): Promise<void> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.put('/api/v1/admin/models/transcription', {
      data: enabled
        ? {
            enabled: true,
            baseUrl: 'http://ai-stub:8089/transcription',
            model: 'ai-stub-whisper',
            language: 'de',
            realtime: {
              enabled: true,
              protocol: 'VLLM',
              url: 'ws://ai-stub:8089/realtime',
              model: 'ai-stub-voxtral',
              maxSessions: 2,
            },
          }
        : { enabled: false, realtime: { enabled: false } },
    })
    expect(response.ok(), `PUT transcription: ${response.status()}`).toBe(true)
  } finally {
    await api.dispose()
  }
}

async function dropLiveSessions(drop: boolean): Promise<void> {
  const response = await fetch(`${AI_STUB_BASE_URL}/control/realtime`, {
    method: 'POST',
    body: JSON.stringify({ drop }),
  })
  expect(response.ok).toBe(true)
}

async function liveSessions(): Promise<{ opened: number; dropped: number; done: number }> {
  const response = await fetch(`${AI_STUB_BASE_URL}/control/realtime`)
  return (await response.json()) as { opened: number; dropped: number; done: number }
}

test.describe.serial('Live-Diktat', () => {
  test.beforeAll(async () => {
    await dropLiveSessions(false)
    await setLiveDictation(true)
    const api = await apiAs('dev-admin')
    try {
      await expect
        .poll(
          async () => {
            const response = await api.get('/api/v1/transcriptions/availability')
            return (await response.json()) as { available: boolean; realtimeAvailable: boolean }
          },
          { timeout: 60_000 },
        )
        .toEqual({ available: true, realtimeAvailable: true })
    } finally {
      await api.dispose()
    }
  })

  test.afterAll(async () => {
    await dropLiveSessions(false)
    await setLiveDictation(false)
  })

  test('1. Der Text erscheint beim Sprechen gedimmt und wird zum Entwurf', async ({
    authenticatedPage: page,
  }) => {
    const before = await liveSessions()
    await startFreshChat(page)

    await page.getByRole('button', { name: 'Spracheingabe starten' }).click()
    const interim = page.getByTestId('dictation-interim')
    await expect(interim).toContainText('Welche Frist', { timeout: 15_000 })
    await expect(interim).toHaveCSS('font-style', 'italic')
    await page.getByRole('button', { name: 'Aufnahme beenden' }).click()

    const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
    await expect(input).toHaveValue(LIVE_TRANSCRIPT)
    await expect(interim).toHaveCount(0)
    expect((await liveSessions()).done).toBe(before.done + 1)
  })

  test('2. Bricht der Live-Dienst ab, gilt die ganze Aufnahme', async ({
    authenticatedPage: page,
  }) => {
    await dropLiveSessions(true)
    const before = await liveSessions()
    await startFreshChat(page)

    await page.getByRole('button', { name: 'Spracheingabe starten' }).click()
    await expect.poll(async () => (await liveSessions()).dropped).toBe(before.dropped + 1)
    await page.getByRole('button', { name: 'Aufnahme beenden' }).click()

    await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toHaveValue(
      BATCH_TRANSCRIPT,
    )
    await expect(page.getByText(/Aufnahme konnte nicht|Aufnahme wurde unterbrochen/)).toHaveCount(0)
  })
})
