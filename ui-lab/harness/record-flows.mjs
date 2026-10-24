#!/usr/bin/env node
/*
 * Video matrix of the source setup (variant F) in MSW mock mode: per connector three flows.
 *
 *   NODE_PATH=$(npm root -g) node record-flows.mjs --base http://localhost:5173 --out <dir>
 *        [--types filesystem,confluence,...] [--kinds ok,viele,fehler]
 *
 *   ok      scenario small:  wizard to „Prüfen“, create, then the tab „Quelle“ with one „Ändern“
 *   viele   scenario scale:  the selection with many entries (levels, search, „Alle n auswählen“),
 *                            to „Prüfen“, then editing the fixture library with its selection
 *   fehler  scenario failed: error summary, failing test or listing, correction / retry
 *
 * 1280x800 WebM per flow, ~600 ms between actions, visible pointer dot, typing with delay.
 * Convert with ffmpeg afterwards (H.264, crf 30). A step that cannot be found is logged and
 * skipped, the flow goes on.
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
const OUT = opt('--out', '/tmp/flows-F')
const TYPES = opt('--types', 'filesystem,confluence,sharepoint,nextcloud,smb,s3,gdrive,dropbox').split(',')
const KINDS = opt('--kinds', 'ok,viele,fehler').split(',')
const PACE = 600
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
fs.mkdirSync(OUT, { recursive: true })

const FILE_NAMES = {
  filesystem: 'dateisystem',
  confluence: 'confluence',
  sharepoint: 'sharepoint',
  nextcloud: 'nextcloud',
  smb: 'smb',
  s3: 's3',
  gdrive: 'googledrive',
  dropbox: 'dropbox',
}
const TILES = {
  filesystem: /^Dateisystem/,
  confluence: /^Confluence/,
  sharepoint: /^SharePoint/,
  nextcloud: /^Nextcloud/,
  smb: /^Windows-Dateifreigabe/,
  s3: /^S3/,
  gdrive: /^Google Drive/,
  dropbox: /^Dropbox/,
}
const LIBRARIES = {
  filesystem: 'library-filesystem-satzungen',
  confluence: 'library-confluence-wiki',
  sharepoint: 'library-sharepoint-rathaus',
  nextcloud: 'library-nextcloud-projekte',
  smb: 'library-smb-ablage',
  s3: 'library-s3-protokolle',
  gdrive: 'library-gdrive-projekte',
  dropbox: 'library-dropbox-projekte',
}

const GDRIVE_KEY = path.join(OUT, '.fake-service-account.json')
fs.writeFileSync(
  GDRIVE_KEY,
  JSON.stringify({
    type: 'service_account',
    project_id: 'rheinfurt-projekt',
    client_email: 'opaa-indexer@rheinfurt-projekt.iam.gserviceaccount.com',
    private_key_id: 'fake0123',
    private_key: '-----BEGIN PRIVATE KEY-----\nFAKE\n-----END PRIVATE KEY-----\n',
  }),
)

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

function actor(page, log) {
  async function find(locator, timeout) {
    try {
      await locator.first().waitFor({ state: 'visible', timeout })
      return locator.first()
    } catch {
      return null
    }
  }
  async function point(el) {
    await el.scrollIntoViewIfNeeded().catch(() => {})
    const box = await el.boundingBox()
    if (box) await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
    await sleep(250)
  }
  const a = {
    page,
    wait: (ms = PACE) => sleep(ms),
    /** Clicks when visible within `timeout`; returns whether it did. */
    async click(locator, what, timeout = 6000) {
      const el = await find(locator, timeout)
      if (!el) {
        log(`  skip: ${what}`)
        return false
      }
      await point(el)
      await el.click().catch((e) => log(`  click failed: ${what}: ${e.message.split('\n')[0]}`))
      await sleep(PACE)
      return true
    },
    async type(locator, text, what) {
      const el = await find(locator, 6000)
      if (!el) {
        log(`  skip: ${what}`)
        return false
      }
      await point(el)
      await el.click()
      await el.pressSequentially(text, { delay: 45 })
      await sleep(PACE)
      return true
    },
    async clear(locator) {
      const el = await find(locator, 2000)
      if (el) await el.fill('')
    },
    visible: async (locator, timeout = 1500) => (await find(locator, timeout)) !== null,
    btn: (name, scope = page) =>
      scope.getByRole('button', { name, exact: typeof name === 'string' }),
    field: (label, scope = page) =>
      scope.getByLabel(label, { exact: typeof label === 'string' }),
  }
  return a
}

const scopeRow = (page) => page.locator('[data-row$="-row-scope"]')
const dialogOf = (page) => page.getByRole('dialog').last()

async function startWizard(a, type, scenario) {
  await a.page.goto(`${BASE}/libraries/new?mockScenario=${scenario}`)
  await a.page.waitForSelector('main')
  await a.wait(1200)
  await a.click(a.page.getByRole('radio', { name: TILES[type] }), 'tile')
  await a.click(a.btn('Weiter'), 'Weiter')
}

/** The fields of „Verbindung“; for Dropbox the consent round trip. */
async function fillConnection(a, type, { path = '/srv/opaa/sozialamt' } = {}) {
  const { page } = a
  switch (type) {
    case 'filesystem':
      await a.type(a.field('Verzeichnis'), path, 'Verzeichnis')
      break
    case 'confluence':
      await a.type(a.field('Adresse'), 'https://rheinfurt.atlassian.net/wiki', 'Adresse')
      await a.wait(1000)
      await a.type(a.field('E-Mail-Adresse'), 'dienstkonto@rheinfurt.example', 'E-Mail')
      await a.type(a.field('API-Token'), 'atl-token-123', 'API-Token')
      break
    case 'sharepoint':
      await a.click(page.getByRole('radio', { name: /Microsoft 365 Rheinfurt/ }), 'Zugang')
      break
    case 'nextcloud':
      await a.click(page.getByRole('radio', { name: /Eigene Adresse/ }), 'Eigene Adresse')
      await a.type(a.field('Adresse'), 'https://cloud.rheinfurt.example', 'Adresse')
      await a.type(a.field('Benutzername'), 'svc-opaa', 'Benutzername')
      await a.type(a.field('App-Passwort'), 'app-passwort', 'App-Passwort')
      break
    case 'smb':
      await a.type(a.field('Adresse'), 'smb://dateiserver.rheinfurt.example/Ablage', 'Adresse')
      await a.type(a.field('Benutzername'), 'RHEINFURT\\svc-opaa', 'Benutzername')
      await a.type(a.field('Passwort'), 'geheim', 'Passwort')
      break
    case 's3':
      await a.type(a.field('Endpoint'), 'https://minio.intern.example:9000', 'Endpoint')
      await a.type(a.field('Access Key'), 'OPAAINDEXER', 'Access Key')
      await a.type(a.field('Secret Key'), 'geheim', 'Secret Key')
      break
    case 'gdrive': {
      const button = page.getByRole('button', { name: 'Datei wählen' })
      if (await a.visible(button)) {
        const box = await button.boundingBox()
        if (box) await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
      }
      await page.locator('input[type=file]').first().setInputFiles(GDRIVE_KEY)
      await a.wait(1000)
      break
    }
    case 'dropbox':
      await a.click(page.getByRole('radio', { name: /Dropbox Rheinfurt/ }), 'Zugang')
      await a.click(page.getByRole('checkbox', { name: /Dienstkonto/ }), 'Dienstkonto')
      await a.click(a.btn('Quelle verbinden'), 'Quelle verbinden')
      await page.waitForURL(/\/libraries\/new/, { timeout: 15000 }).catch(() => {})
      await page.waitForSelector('main')
      await a.wait(2000)
      break
  }
}

/** Closes „Verbindung“ (which runs the test) and makes sure „Umfang“ is open. */
async function toScope(a) {
  await a.click(a.btn('Übernehmen'), 'Übernehmen Verbindung')
  await a.wait(1200)
  const row = scopeRow(a.page)
  if (!(await a.visible(row.getByRole('button', { name: 'Übernehmen' }), 800))) {
    await a.click(a.btn('Ändern Umfang'), 'Ändern Umfang')
  }
  return row
}

async function finishToReview(a, name, linger = 2000) {
  await a.click(a.btn('Weiter'), 'Weiter')
  await a.wait(800)
  await a.type(a.field(/^Name/), name, 'Name')
  for (let i = 0; i < 3; i++) {
    if (await a.visible(a.page.getByRole('heading', { name: 'Prüfen', exact: true }), 500)) break
    await a.click(a.btn('Weiter'), 'Weiter')
    await a.wait(600)
  }
  await a.wait(linger)
}

async function openRows(a) {
  await a.click(a.page.getByRole('tab', { name: /^Quelle/ }), 'Reiter Quelle')
  await a.wait(1000)
}

// ---- ok: small ---------------------------------------------------------------------------------

async function scopeSmall(a, type) {
  const { page } = a
  const row = await toScope(a)
  if (type === 'filesystem') {
    await a.type(a.field(/^Ausschlussmuster/), 'Archiv/**', 'Ausschlussmuster')
  } else if (type === 'sharepoint') {
    await a.click(a.btn('Auswählen …', row), 'Auswählen')
    const d = dialogOf(page)
    await a.wait(800)
    await a.click(d.getByRole('button', { name: '„Rathaus“ öffnen' }), 'Rathaus öffnen')
    await a.wait(800)
    await a.click(d.getByRole('checkbox', { name: 'Dokumente' }), 'Dokumente')
    await a.click(a.btn('Übernehmen', d), 'Übernehmen Dialog')
  } else {
    if (await a.visible(row.getByRole('radio').first(), 800)) {
      await a.click(row.getByRole('radio', { name: /^Nur ausgewählte/ }), 'Nur ausgewählte')
      await a.wait(800)
    }
    const boxes = row.getByRole('group').getByRole('checkbox')
    await a.click(boxes.nth(0), 'erste Checkbox')
    if ((await boxes.count()) > 1) await a.click(boxes.nth(1), 'zweite Checkbox')
  }
  await a.click(a.btn('Übernehmen', row), 'Übernehmen Umfang')
  await a.wait(1000)
}

async function flowOk(a, type) {
  await startWizard(a, type, 'small')
  await fillConnection(a, type)
  await scopeSmall(a, type)
  await finishToReview(a, `${FILE_NAMES[type]} Projektablage`)
  await a.click(a.btn('Bibliothek anlegen'), 'Bibliothek anlegen')
  await a.page.waitForURL(/\/libraries\/(?!new)/, { timeout: 10000 }).catch(() => {})
  await a.wait(2000)
  await openRows(a)
  await a.click(a.btn('Ändern Zeitplan'), 'Ändern Zeitplan')
  await a.wait(1000)
  const select = a.page.locator('[data-row$="-row-schedule"]').getByRole('combobox').first()
  if (await a.click(select, 'Häufigkeit')) {
    await a.click(a.page.getByRole('option', { name: /Wöchentlich/ }), 'Wöchentlich')
  }
  await a.click(a.btn('Speichern', a.page.locator('[data-row$="-row-schedule"]')), 'Speichern')
  await a.wait(2500)
}

// ---- viele: scale ------------------------------------------------------------------------------

async function pickAllHits(a, d, term) {
  await a.clear(d.getByRole('searchbox'))
  await a.type(d.getByRole('searchbox'), term, 'Suche')
  await a.wait(900)
  await a.click(d.getByText(/^Alle \d+ (Treffer )?auswählen$/), 'Alle n auswählen')
}

async function scopeScale(a, type) {
  const { page } = a
  const row = await toScope(a)
  if (type === 'filesystem') {
    await a.type(
      a.field(/^Ausschlussmuster/),
      'Archiv/**\n**/Entwurf_*/**\n**/*.tmp\nFachbereich-Soziales/Eingliederungshilfe/Altakten-2009-2014/**',
      'Ausschlussmuster',
    )
    await a.click(a.btn('Übernehmen', row), 'Übernehmen Umfang')
    return
  }
  if (await a.visible(row.getByRole('radio').first(), 800)) {
    await a.click(row.getByRole('radio', { name: /^Nur ausgewählte/ }), 'Nur ausgewählte')
    await a.wait(1200)
  }
  await a.click(a.btn('Auswählen …', row), 'Auswählen')
  const d = dialogOf(page)
  await a.wait(1200)
  const open = (name) => d.getByRole('button', { name: `„${name}“ öffnen` })
  switch (type) {
    case 'confluence':
      await pickAllHits(a, d, 'Bauamt')
      break
    case 'gdrive':
      await pickAllHits(a, d, 'Ordner 01')
      break
    case 'sharepoint':
      await a.type(d.getByRole('searchbox'), 'Rathaus', 'Site suchen')
      await a.wait(1200)
      await a.click(open('Rathaus'), 'Rathaus öffnen')
      await a.wait(1000)
      await a.click(d.getByRole('checkbox', { name: 'Dokumentbibliothek 002' }), 'Bibliothek 002')
      await a.click(open('Dokumente'), 'Dokumente öffnen')
      await a.wait(1000)
      await pickAllHits(a, d, 'Ordner 00')
      await a.click(d.getByRole('navigation').getByRole('button', { name: 'Rathaus' }), 'zurück zu Rathaus')
      await a.wait(1200)
      break
    case 's3':
      await a.click(open('bucket-0001'), 'bucket-0001 öffnen')
      await a.wait(1000)
      await pickAllHits(a, d, 'ordner-00')
      await a.click(d.getByRole('navigation').getByRole('button', { name: 'Buckets' }), 'zurück zu Buckets')
      await a.wait(1200)
      break
    default:
      await a.click(open('Ordner 001'), 'Ordner 001 öffnen')
      await a.wait(1000)
      await pickAllHits(a, d, 'Vorgang 00')
      await a.click(d.getByRole('navigation').getByRole('button').first(), 'zurück zur Wurzel')
      await a.wait(1200)
  }
  await a.click(a.btn('Übernehmen', d), 'Übernehmen Dialog')
  await a.wait(1200)
  await a.click(a.btn('Übernehmen', row), 'Übernehmen Umfang')
}

async function flowViele(a, type) {
  await startWizard(a, type, 'scale')
  await fillConnection(a, type, {
    path: '/srv/opaa/grundstuecksverkehrsgenehmigungsakte/fachbereich-soziales-jugend-und-familie/eingliederungshilfe',
  })
  await scopeScale(a, type)
  await finishToReview(a, `${FILE_NAMES[type]} Gesamt`, 1200)
  // editing the stored library of the type: its selection is preselected
  await a.page.goto(`${BASE}/libraries/${LIBRARIES[type]}?mockScenario=scale`)
  await a.page.waitForSelector('main')
  await a.wait(1000)
  await openRows(a)
  await a.click(a.btn('Ändern Umfang'), 'Ändern Umfang')
  await a.wait(1200)
  const row = scopeRow(a.page)
  if (type !== 'filesystem' && (await a.click(a.btn('Ändern', row), 'Ändern Auswahl', 2500))) {
    await a.wait(2500)
    await a.click(a.btn('Abbrechen', dialogOf(a.page)), 'Abbrechen')
  }
  await a.wait(1000)
}

// ---- fehler: failed ----------------------------------------------------------------------------

async function flowFehler(a, type) {
  const { page } = a
  await startWizard(a, type, 'failed')
  if (type === 'dropbox' || type === 'sharepoint') {
    // the Zugang first, then the empty scope runs into the summary
    await fillConnection(a, type)
    await a.click(a.btn('Weiter'), 'Weiter (leer)')
    await a.wait(1500)
  } else {
    await a.click(a.btn('Weiter'), 'Weiter (leer)')
    await a.wait(1500)
    await a.click(page.getByRole('alert').getByRole('button').first(), 'erster Eintrag der Fehlerzusammenfassung')
    await fillConnection(a, type)
  }
  if (!(await a.visible(a.btn('Verbindung testen'), 800))) {
    await a.click(a.btn('Ändern Verbindung'), 'Ändern Verbindung')
  }
  await a.click(a.btn('Verbindung testen'), 'Verbindung testen')
  await a.wait(2500)
  await a.click(a.btn('Trotzdem fortfahren'), 'Trotzdem fortfahren', 2000)
  const row = await toScope(a)
  if (type === 'filesystem') {
    await a.type(a.field(/^Ausschlussmuster/), 'Archiv/**', 'Ausschlussmuster')
  } else {
    if (await a.visible(row.getByRole('radio').first(), 800)) {
      await a.click(row.getByRole('radio', { name: /^Nur ausgewählte/ }), 'Nur ausgewählte')
      await a.wait(1500)
    }
    await a.click(a.btn('Erneut laden', row), 'Erneut laden', 3000)
    await a.wait(1500)
    const manualTexts = {
      s3: 'protokolle/2026/',
      gdrive: '1AbCdEfGhIjKlMnOp',
      nextcloud: '/Projekte/2026',
      smb: '/Projekte/2026',
      dropbox: '/Projekte/2026',
      sharepoint: 'https://rheinfurt.sharepoint.com/sites/rathaus',
    }
    const opener = (await a.visible(a.btn('Von Hand eintragen …', row), 800))
      ? a.btn('Von Hand eintragen …', row)
      : a.btn('Auswählen …', row)
    if (manualTexts[type] && (await a.click(opener, 'Dialog öffnen', 2000))) {
      const d = dialogOf(page)
      await a.wait(1200)
      if (!(await a.visible(d.getByRole('textbox').last(), 600))) {
        await a.click(d.getByRole('button', { name: /von Hand eintragen$/ }), 'von Hand eintragen')
      }
      await a.type(d.locator('#selection-manual'), manualTexts[type], 'Eingabe von Hand')
      await a.click(d.getByRole('button', { name: /^(Hinzufügen|Öffnen)$/ }), 'Hinzufügen')
      await a.wait(1500)
      await a.click(a.btn(type === 'sharepoint' ? 'Abbrechen' : 'Übernehmen', d), 'Dialog schließen')
    }
  }
  await a.click(a.btn('Übernehmen', row), 'Übernehmen Umfang', 2000)
  await a.wait(800)
  await a.click(a.btn('Weiter'), 'Weiter')
  await a.wait(2500)
}

const FLOWS = { ok: flowOk, viele: flowViele, fehler: flowFehler }

const browser = await chromium.launch()
for (const type of TYPES) {
  for (const kind of KINDS) {
    const name = `${FILE_NAMES[type]}__${kind}`
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      locale: 'de-DE',
      recordVideo: { dir: OUT, size: { width: 1280, height: 800 } },
    })
    await context.addInitScript(POINTER)
    const page = await context.newPage()
    const notes = []
    const log = (line) => notes.push(line)
    const t0 = Date.now()
    try {
      await FLOWS[kind](actor(page, log), type)
      console.log(`ok   ${name} ${((Date.now() - t0) / 1000).toFixed(0)}s`)
    } catch (err) {
      console.log(`FAIL ${name}: ${String(err.message).split('\n')[0]}`)
    }
    for (const line of notes) console.log(line)
    const video = page.video()
    await context.close()
    if (video) fs.renameSync(await video.path(), path.join(OUT, `${name}.webm`))
  }
}
await browser.close()
fs.rmSync(GDRIVE_KEY, { force: true })
