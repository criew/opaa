import { sleep } from '../lib/browser.mjs'
import { clickButton, fillByLabel, firstVisible, settle } from '../lib/ui.mjs'

/**
 * Text recognition (epic #2640). Type `models`: the tab „Texterkennung“ of the model
 * administration with its connection test, a blocked internal address and the vision binding.
 * Type `beleg`: the chat answer citing scans, its Belegfenster and „Dokument öffnen“ on the scan
 * with frames (docling-serve) and on the scan read by a vision model (page only).
 */

export const description = 'Texterkennung: Reiter in der Modellverwaltung, Beleg und Scan-Viewer'
export const DEFAULT_SCENARIOS = ['small', 'default', 'scale', 'empty', 'failed', 'partial', 'slow']
export const QUICK_SCENARIOS = ['small', 'default']
export const AREAS = ['tab', 'test', 'blocked', 'vision', 'drawer', 'viewer', 'viewer-vision']

const MODELS_PATH = '/admin/models/text-recognition'
const CHAT_PATH = '/spaces/space-personal/chats/chat-scan'

export function jobs({ scenarios }) {
  return scenarios.flatMap((scenario) =>
    ['models', ...(scenario === 'default' || scenario === 'small' ? ['beleg'] : [])].map(
      (type) => ({ type, scenario }),
    ),
  )
}

const want = (ctx, area) => !ctx.areas || ctx.areas.includes(area)

/** Variant B keeps the connection in a dialog: open it when the fields are not on the page. */
async function openConnection(page) {
  if (await firstVisible(page.getByLabel(/^Adresse/))) return true
  return clickButton(page, /^(Ändern|Einrichten)$/, { wait: 600 })
}

async function runModels(page, job, ctx) {
  await page.goto(`${ctx.base}${MODELS_PATH}?mockScenario=${job.scenario}`, {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
  await settle(page, 900)
  if (want(ctx, 'tab')) await ctx.shoot('tab')
  if (job.scenario === 'partial' || job.scenario === 'slow') return

  if (want(ctx, 'test')) {
    if (await openConnection(page)) {
      const field = await firstVisible(page.getByLabel(/^Adresse/))
      if (field && (await field.inputValue()) === '') {
        await fillByLabel(page, [/^Adresse/], 'http://opaa-ocr:5001')
      }
      if (await clickButton(page, /^Verbindung testen$/, { wait: 1400 })) await ctx.shoot('test')
      else ctx.notFound('test', 'no test button')
    } else ctx.notFound('test', 'no connection fields')
  }

  if (!['small', 'default'].includes(job.scenario)) return

  if (want(ctx, 'blocked')) {
    await openConnection(page)
    await fillByLabel(page, [/^Adresse/], 'http://10.20.0.5:5001')
    if (await clickButton(page, /^Speichern$/, { wait: 900 })) await ctx.shoot('blocked')
    else ctx.notFound('blocked', 'no save button')
    await fillByLabel(page, [/^Adresse/], 'http://vllm:8000/v1')
  }

  if (want(ctx, 'vision')) {
    await openConnection(page)
    let chosen = false
    const radio = await firstVisible(page.getByRole('radio', { name: /Vision-Modell/ }))
    if (radio) {
      await radio.click().catch(() => {})
      chosen = true
    } else {
      const select = await firstVisible(page.getByLabel(/^Anbindung$/))
      if (select) {
        await select.selectOption('OPENAI_VISION').catch(() => {})
        chosen = true
      }
    }
    await sleep(400)
    if (chosen) await ctx.shoot('vision')
    else ctx.notFound('vision', 'no binding choice')
  }
}

async function openDrawer(page) {
  if (await firstVisible(page.getByRole('dialog', { name: 'Belege dieser Antwort' }))) return true
  return clickButton(page, /Belege anzeigen/, { wait: 900 })
}

async function openDocument(page, fileName) {
  const rows = page.locator(`[data-testid="evidence-doc"][data-file="${fileName}"]`)
  await rows.first().waitFor({ state: 'visible', timeout: 4000 }).catch(() => {})
  const row = await firstVisible(rows)
  if (!row) return false
  const link = await firstVisible(row.getByRole('button', { name: 'Dokument öffnen' }))
  if (!link) return false
  await link.click().catch(() => {})
  await page.getByTestId('pdf-container').waitFor({ timeout: 8000 }).catch(() => {})
  await sleep(2500)
  return true
}

async function runBeleg(page, job, ctx) {
  await page.goto(`${ctx.base}${CHAT_PATH}?mockScenario=${job.scenario}`, {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
  await settle(page, 900)
  if (!(await openDrawer(page))) {
    for (const area of ['drawer', 'viewer', 'viewer-vision']) ctx.notFound(area, 'no Belegfenster')
    return
  }
  if (want(ctx, 'drawer')) await ctx.shoot('drawer')

  if (want(ctx, 'viewer')) {
    if (await openDocument(page, 'Verwaltungsgebuehrensatzung_2003_Scan.pdf')) await ctx.shoot('viewer')
    else ctx.notFound('viewer', 'no scan row')
    await page.keyboard.press('Escape')
    await sleep(1000)
  }

  if (want(ctx, 'viewer-vision')) {
    await page.goto(`${ctx.base}${CHAT_PATH}?mockScenario=${job.scenario}`, {
      waitUntil: 'domcontentloaded',
    })
    await settle(page, 900)
    await openDrawer(page)
    if (await openDocument(page, 'Bauakte_Hauptstrasse_12_1998_Scan.pdf')) {
      await ctx.shoot('viewer-vision')
    } else ctx.notFound('viewer-vision', 'no vision row')
  }
}

export async function run(page, job, ctx) {
  if (job.type === 'models') await runModels(page, job, ctx)
  else await runBeleg(page, job, ctx)
}
