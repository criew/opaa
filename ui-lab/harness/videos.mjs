#!/usr/bin/env node
/*
 * Records short behaviour videos of the source setup (variant F) in MSW mock mode.
 *
 *   NODE_PATH=$(npm root -g) node videos.mjs --base http://localhost:5173 --out <dir> [--only 1,3]
 *
 * One WebM per flow (1280x800), unhurried pace (~600 ms between actions) and a visible pointer dot.
 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { chromium } = require('playwright')

const args = process.argv.slice(2)
const opt = (name, fallback) => {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : fallback
}
const BASE = opt('--base', 'http://localhost:5173')
const OUT = opt('--out', '/tmp/videos-F')
const ONLY = opt('--only', null)?.split(',').map(Number)
const PACE = 600
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
fs.mkdirSync(OUT, { recursive: true })

const POINTER = `
  window.addEventListener('DOMContentLoaded', () => {
    const dot = document.createElement('div')
    dot.style.cssText = 'position:fixed;z-index:2147483647;width:18px;height:18px;margin:-9px 0 0 -9px;' +
      'border-radius:50%;background:rgba(220,40,40,.55);border:2px solid #fff;box-shadow:0 0 0 1px rgba(0,0,0,.35);' +
      'pointer-events:none;left:-40px;top:-40px;transition:transform .12s'
    document.body.appendChild(dot)
    addEventListener('mousemove', (e) => { dot.style.left = e.clientX + 'px'; dot.style.top = e.clientY + 'px' }, true)
    addEventListener('mousedown', () => { dot.style.transform = 'scale(.7)' }, true)
    addEventListener('mouseup', () => { dot.style.transform = '' }, true)
  })
`

function helpers(page) {
  async function point(locator) {
    await locator.waitFor({ state: 'visible', timeout: 10000 })
    await locator.scrollIntoViewIfNeeded()
    const box = await locator.boundingBox()
    if (box) await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
    await sleep(250)
  }
  return {
    async click(locator) {
      await point(locator)
      await locator.click()
      await sleep(PACE)
    },
    async type(locator, text) {
      await point(locator)
      await locator.click()
      await locator.pressSequentially(text, { delay: 45 })
      await sleep(PACE)
    },
    async hover(locator) {
      await point(locator)
      await sleep(PACE)
    },
    button: (name, scope = page) => scope.getByRole('button', { name, exact: typeof name === 'string' }).first(),
    wait: (ms = PACE) => sleep(ms),
  }
}

async function startWizard(page, h, scenario, tile) {
  await page.goto(`${BASE}/libraries/new?mockScenario=${scenario}`)
  await page.waitForSelector('main')
  await sleep(1200)
  await h.click(page.getByRole('radio', { name: tile }).or(page.getByRole('button', { name: tile })).first())
  await h.click(h.button('Weiter'))
}

async function finishWizard(page, h, name) {
  await h.click(h.button('Weiter'))
  await h.type(page.getByLabel(/^Name/).first(), name)
  for (let i = 0; i < 3; i++) {
    if (await page.getByRole('heading', { name: 'Prüfen', exact: true }).isVisible().catch(() => false)) break
    await h.click(h.button('Weiter'))
  }
  await sleep(2500)
}

const scopeRow = (page) => page.locator('[data-row$="-row-scope"]')

const FLOWS = {
  1: ['01-confluence-wenige-eintraege', async (page, h) => {
    await startWizard(page, h, 'small', /^Confluence/)
    await h.type(page.getByLabel('Adresse', { exact: true }).first(), 'https://rheinfurt.atlassian.net/wiki')
    await h.wait(1200)
    await h.type(page.getByLabel('E-Mail-Adresse', { exact: true }).first(), 'dienstkonto@rheinfurt.example')
    await h.type(page.getByLabel('API-Token', { exact: true }).first(), 'atl-token-123')
    await h.click(h.button('Übernehmen'))
    await h.wait(1200)
    const boxes = scopeRow(page).getByRole('checkbox')
    await h.click(boxes.nth(0))
    await h.click(boxes.nth(1))
    await h.click(h.button('Übernehmen'))
    await h.wait(1000)
    await finishWizard(page, h, 'Verwaltungswiki')
  }],
  2: ['02-confluence-viele-eintraege-dialog', async (page, h) => {
    await startWizard(page, h, 'scale', /^Confluence/)
    await h.type(page.getByLabel('Adresse', { exact: true }).first(), 'https://rheinfurt.atlassian.net/wiki')
    await h.wait(1200)
    await h.type(page.getByLabel('E-Mail-Adresse', { exact: true }).first(), 'dienstkonto@rheinfurt.example')
    await h.type(page.getByLabel('API-Token', { exact: true }).first(), 'atl-token-123')
    await h.click(h.button('Übernehmen'))
    await h.wait(1500)
    await h.click(h.button('Auswählen …', scopeRow(page)))
    const dialog = page.getByRole('dialog')
    await h.type(dialog.getByRole('searchbox'), 'Bauamt')
    await h.wait(800)
    await h.click(dialog.getByText(/^Alle \d+ Treffer auswählen$/))
    await h.wait(800)
    await dialog.getByRole('searchbox').fill('')
    await h.type(dialog.getByRole('searchbox'), 'Gibt es nicht')
    await h.wait(800)
    await h.click(h.button('Suche zurücksetzen', dialog))
    await h.click(h.button('Auswahl ansehen', dialog))
    await h.wait(1200)
    await h.click(h.button('Übernehmen', dialog))
    await h.wait(2000)
  }],
  3: ['03-sharepoint-site-bibliothek-ordner', async (page, h) => {
    await startWizard(page, h, 'scale', /^SharePoint/)
    await h.click(page.getByRole('radio', { name: /Microsoft 365 Rheinfurt/ }).first())
    await h.click(h.button('Übernehmen'))
    await h.wait(1200)
    const row = scopeRow(page)
    if (!(await row.getByRole('button', { name: 'Auswählen …' }).isVisible().catch(() => false))) {
      await h.click(h.button('Ändern Umfang'))
    }
    await h.click(h.button('Auswählen …', row))
    const dialog = page.getByRole('dialog')
    await h.type(dialog.getByRole('searchbox'), 'Rathaus')
    await h.wait(1200)
    await h.click(dialog.getByRole('button', { name: '„Rathaus“ öffnen' }))
    await h.wait(1000)
    await h.click(dialog.getByRole('button', { name: '„Dokumente“ öffnen' }))
    await h.wait(1000)
    const boxes = dialog.getByRole('checkbox', { name: /^Ordner 00[12]$/ })
    await h.click(boxes.nth(0))
    await h.click(boxes.nth(1))
    await h.click(dialog.getByRole('navigation').getByRole('button', { name: 'Rathaus' }))
    await h.wait(1500)
    await h.click(dialog.getByRole('checkbox', { name: 'Dokumentbibliothek 002' }))
    await h.click(dialog.getByRole('navigation').getByRole('button', { name: 'Sites' }))
    await h.wait(1500)
    await h.click(h.button('Auswahl ansehen', dialog))
    await h.wait(1500)
    await h.click(h.button('Übernehmen', dialog))
    await h.wait(2500)
  }],
  4: ['04-nextcloud-radiopaar-checkboxen', async (page, h) => {
    await startWizard(page, h, 'small', /^Nextcloud/)
    await h.click(page.getByRole('radio', { name: /Eigene Adresse/ }).first())
    await h.type(page.getByLabel('Adresse', { exact: true }).first(), 'https://cloud.rheinfurt.example')
    await h.type(page.getByLabel('Benutzername', { exact: true }).first(), 'svc-opaa')
    await h.type(page.getByLabel('App-Passwort', { exact: true }).first(), 'app-passwort')
    await h.click(h.button('Übernehmen'))
    await h.wait(1200)
    const row = scopeRow(page)
    if (!(await row.getByRole('radio').first().isVisible().catch(() => false))) {
      await h.click(h.button('Ändern Umfang'))
    }
    await h.click(row.getByRole('radio', { name: 'Nur ausgewählte Ordner' }))
    await h.wait(1000)
    await h.click(row.getByRole('checkbox', { name: 'Ordner 001' }))
    await h.click(row.getByRole('checkbox', { name: 'Ordner 002' }))
    await h.click(row.getByRole('radio', { name: 'Alle Ordner' }))
    await h.click(row.getByRole('radio', { name: 'Nur ausgewählte Ordner' }))
    await h.click(row.getByRole('checkbox', { name: 'Ordner 002' }))
    await h.click(h.button('Übernehmen', row))
    await h.wait(1500)
    await finishWizard(page, h, 'Projektablage')
  }],
  5: ['05-s3-fehler-und-korrektur', async (page, h) => {
    await startWizard(page, h, 'failed', /^S3/)
    await h.type(page.getByLabel('Endpoint', { exact: true }).first(), 'https://minio.intern.example:9000')
    await h.type(page.getByLabel('Access Key', { exact: true }).first(), 'OPAAINDEXER')
    await h.click(h.button('Weiter'))
    await h.wait(1500)
    await h.click(page.getByRole('button', { name: 'Verbindung: Secret Key fehlt' }))
    await h.type(page.getByLabel('Secret Key', { exact: true }).first(), 'falscher-schluessel')
    await h.click(h.button('Verbindung testen'))
    await h.wait(2500)
    await h.click(h.button('Trotzdem fortfahren'))
    await h.click(h.button('Übernehmen'))
    await h.wait(1500)
    const row = scopeRow(page)
    await h.click(h.button('Von Hand eintragen …', row))
    const dialog = page.getByRole('dialog')
    await h.type(dialog.getByLabel('Bucket/Präfix', { exact: true }).first(), 'protokolle/2026/')
    await h.click(h.button('Hinzufügen', dialog))
    await h.click(h.button('Übernehmen', dialog))
    await h.wait(2500)
  }],
  6: ['06-sharepoint-bearbeiten-vorausgewaehlt', async (page, h) => {
    await page.goto(`${BASE}/libraries/library-sharepoint-rathaus?mockScenario=scale`)
    await page.waitForSelector('main')
    await sleep(1500)
    await h.click(page.getByRole('tab', { name: /^Quelle/ }))
    await h.wait(800)
    await h.click(h.button('Ändern Umfang'))
    await h.wait(1000)
    await h.click(h.button('Ändern', scopeRow(page)))
    const dialog = page.getByRole('dialog')
    await h.wait(1500)
    await h.click(dialog.getByRole('button', { name: '„Rathaus“ öffnen' }))
    await h.wait(1200)
    await h.wait(1500)
    await h.click(dialog.getByRole('button', { name: /^„Dokumente.*öffnen$/ }).first())
    await h.wait(3000)
    await h.click(h.button('Auswahl ansehen', dialog))
    await h.wait(2000)
    await h.click(h.button('Abbrechen', dialog))
    await h.wait(1500)
  }],
  8: ['08-sharepoint-bearbeiten-eine-site', async (page, h) => {
    await page.goto(`${BASE}/libraries/library-sharepoint-rathaus?mockScenario=small`)
    await page.waitForSelector('main')
    await sleep(1500)
    await h.click(page.getByRole('tab', { name: /^Quelle/ }))
    await h.wait(800)
    await h.click(h.button('Ändern Umfang'))
    await h.wait(1000)
    await h.click(h.button('Ändern', scopeRow(page)))
    const dialog = page.getByRole('dialog')
    await h.wait(2500)
    await h.click(dialog.getByRole('checkbox', { name: 'Dokumente' }))
    await h.click(dialog.getByRole('button', { name: '„Dokumente“ öffnen' }))
    await h.wait(1500)
    await h.click(dialog.getByRole('checkbox', { name: 'Ordner 001' }))
    await h.click(dialog.getByRole('navigation').getByRole('button', { name: 'Rathaus' }))
    await h.wait(1500)
    await h.click(h.button('Übernehmen', dialog))
    await h.wait(1500)
    await h.click(h.button('Speichern', scopeRow(page)))
    await h.wait(2500)
  }],
  7: ['07-reiter-quelle-aendern-an-ort-und-stelle', async (page, h) => {
    await page.goto(`${BASE}/libraries/library-nextcloud-projekte?mockScenario=small`)
    await page.waitForSelector('main')
    await sleep(1500)
    await h.click(page.getByRole('tab', { name: /^Quelle/ }))
    await h.wait(800)
    await h.click(h.button('Ändern Umfang'))
    const row = scopeRow(page)
    await h.click(row.getByRole('radio', { name: 'Nur ausgewählte Ordner' }))
    await h.wait(1200)
    await h.click(row.getByRole('checkbox').first())
    await h.click(h.button('Ändern Zeitplan'))
    await h.wait(800)
    await h.click(h.button('Abbrechen', page.getByRole('dialog')))
    await h.click(h.button('Speichern', row))
    await h.wait(800)
    const toast = page.locator('.MuiSnackbar-root')
    await h.hover(toast)
    await h.wait(7000)
    await page.mouse.move(640, 300, { steps: 12 })
    await h.wait(1200)
    await h.click(h.button('Ändern Zeitplan'))
    await h.wait(2000)
  }],
}

const browser = await chromium.launch()
for (const [n, [name, flow]] of Object.entries(FLOWS)) {
  if (ONLY && !ONLY.includes(Number(n))) continue
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    locale: 'de-DE',
    recordVideo: { dir: OUT, size: { width: 1280, height: 800 } },
  })
  await context.addInitScript(POINTER)
  const page = await context.newPage()
  const t0 = Date.now()
  try {
    await flow(page, helpers(page))
    console.log(`ok   ${name} ${((Date.now() - t0) / 1000).toFixed(0)}s`)
  } catch (err) {
    console.log(`FAIL ${name}: ${String(err.message).split('\n')[0]}`)
    await page.screenshot({ path: path.join(OUT, `${name}__fail.png`) }).catch(() => {})
  }
  const video = page.video()
  await context.close()
  if (video) fs.renameSync(await video.path(), path.join(OUT, `${name}.webm`))
}
await browser.close()
