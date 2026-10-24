#!/usr/bin/env node
/*
 * UI-lab capture harness for the library source flows of the OPAA frontend in MSW mock mode.
 *
 *   node capture.mjs --base http://localhost:5191 --out <dir>
 *        [--types filesystem,confluence,...] [--scenarios small,scale,...]
 *        [--schemes light,dark] [--widths 1280,320] [--areas wizard,review,detail,edit]
 *        [--quick] [--no-axe] [--no-full] [--concurrency 2]
 *
 * Per (type, scenario) one flow runs in a fresh page load (mock state and scenario reset):
 * wizard step "Quelle einrichten" (empty / validation / filled), review step, then the fixture
 * library of the type: detail tab "Quelle" and its edit flow. At every state the page is shot in
 * every scheme (emulated prefers-color-scheme, theme "system") and width, as viewport screenshot
 * plus a "__full" variant, and one metrics JSON line is appended to <out>/metrics.jsonl.
 * Elements are found by role and German label text, so differently designed variants work; a
 * state that cannot be reached is recorded as {"status":"not-found"} instead of failing the run.
 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { execSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)

function loadPlaywright() {
  try {
    return require('playwright')
  } catch {
    const root = execSync('npm root -g').toString().trim()
    return require(path.join(root, 'playwright'))
  }
}
const { chromium } = loadPlaywright()
const AXE_SOURCE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8')

// ---------------------------------------------------------------------------------------------
// arguments
// ---------------------------------------------------------------------------------------------

const ALL_TYPES = ['filesystem', 'confluence', 'sharepoint', 'nextcloud', 'smb', 's3', 'gdrive', 'dropbox']
const DEFAULT_SCENARIOS = ['small', 'scale', 'empty', 'slow', 'failed', 'partial']

function parseArgs(argv) {
  const args = { base: 'http://localhost:5191', out: path.join(HERE, 'out') }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    if (a === '--base') args.base = next()
    else if (a === '--out') args.out = next()
    else if (a === '--types') args.types = next().split(',')
    else if (a === '--scenarios') args.scenarios = next().split(',')
    else if (a === '--schemes') args.schemes = next().split(',')
    else if (a === '--widths') args.widths = next().split(',').map(Number)
    else if (a === '--areas') args.areas = next().split(',')
    else if (a === '--quick') args.quick = true
    else if (a === '--no-axe') args.noAxe = true
    else if (a === '--no-full') args.noFull = true
    else if (a === '--concurrency') args.concurrency = Number(next())
    else if (a === '--help' || a === '-h') {
      console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0])
      process.exit(0)
    } else throw new Error(`unknown argument ${a}`)
  }
  if (args.quick) {
    args.schemes ??= ['light']
    args.widths ??= [1280]
    args.scenarios ??= ['small', 'scale']
  }
  args.types ??= ALL_TYPES
  args.scenarios ??= DEFAULT_SCENARIOS
  args.schemes ??= ['light', 'dark']
  args.widths ??= [1280, 320]
  args.areas ??= ['wizard', 'review', 'detail', 'edit']
  args.concurrency ??= 2
  return args
}

const ARGS = parseArgs(process.argv.slice(2))
fs.mkdirSync(ARGS.out, { recursive: true })
const METRICS = path.join(ARGS.out, 'metrics.jsonl')
const HEIGHT = 900
const T0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - T0) / 1000).toFixed(0).padStart(5)}s]`, ...a)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const wantArea = (a) => ARGS.areas.includes(a)

// ---------------------------------------------------------------------------------------------
// source types: tile label, fixture library, how to fill the source step
// ---------------------------------------------------------------------------------------------

const GDRIVE_KEY = path.join(ARGS.out, '.fake-service-account.json')
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

const TYPES = {
  filesystem: { tile: /^Dateisystem/, library: 'library-filesystem-satzungen' },
  confluence: { tile: /^Confluence/, library: 'library-confluence-wiki' },
  sharepoint: { tile: /^SharePoint/, library: 'library-sharepoint-rathaus', profile: /Microsoft 365 Rheinfurt/ },
  nextcloud: { tile: /^Nextcloud/, library: 'library-nextcloud-projekte', ownAddress: true },
  smb: { tile: /^Windows-Dateifreigabe|^SMB|Dateifreigabe/, library: 'library-smb-ablage' },
  s3: { tile: /^S3/, library: 'library-s3-protokolle' },
  gdrive: { tile: /^Google Drive/, library: 'library-gdrive-projekte' },
  dropbox: { tile: /^Dropbox/, library: 'library-dropbox-projekte', profile: /Dropbox Rheinfurt/ },
}

/** States shot per scenario; the non-structural scenarios only need the loaded source step + detail. */
function statesFor(scenario) {
  if (scenario === 'small') return ['wizard-empty', 'wizard-validation', 'wizard-filled', 'review', 'detail', 'edit']
  if (scenario === 'default') return ['wizard-empty', 'wizard-filled', 'review', 'detail', 'edit']
  if (scenario === 'scale') return ['wizard-filled', 'review', 'detail', 'edit']
  if (scenario === 'failed') return ['wizard-filled', 'detail', 'edit']
  return ['wizard-filled', 'detail']
}

// ---------------------------------------------------------------------------------------------
// robust element helpers
// ---------------------------------------------------------------------------------------------

const ACTION_TIMEOUT = 2500

async function firstVisible(locator) {
  const n = Math.min(await locator.count().catch(() => 0), 30)
  for (let i = 0; i < n; i++) {
    const el = locator.nth(i)
    if (await el.isVisible().catch(() => false)) return el
  }
  return null
}

/** Clicks the first visible element of any of `roles` whose accessible name matches `name`. */
async function clickByName(page, roles, name, { wait = 400, exact = false } = {}) {
  for (const role of roles) {
    const el = await firstVisible(page.getByRole(role, { name, exact }))
    if (el) {
      try {
        await el.click({ timeout: ACTION_TIMEOUT })
        await sleep(wait)
        return true
      } catch {
        /* try the next */
      }
    }
  }
  return false
}

const clickButton = (page, name, opts) => clickByName(page, ['button', 'link'], name, opts)

/** Fills the first visible, enabled field whose label matches one of `labels`. */
async function fillByLabel(page, labels, value) {
  for (const label of [].concat(labels)) {
    const el = await firstVisible(page.getByLabel(label))
    if (!el) continue
    try {
      if (!(await el.isEditable())) continue
      await el.fill(value, { timeout: ACTION_TIMEOUT })
      await sleep(80)
      return true
    } catch {
      /* next */
    }
  }
  return false
}

/** Ticks up to `n` visible unchecked checkboxes / options that look like source objects. */
async function selectSome(page, n) {
  const EXCLUDE = /sofort|Indizierung|bestätige|Dienstkonto|Team|Path-Style|alle|Alle|Erste|Pfad-Stil|Diagnose|Benachrichtig/
  let picked = 0
  // an open listbox (autocomplete) first
  const options = page.getByRole('option')
  const optionCount = Math.min(await options.count().catch(() => 0), 200)
  for (let i = 0; i < optionCount && picked < n; i++) {
    const o = options.nth(i)
    if (!(await o.isVisible().catch(() => false))) continue
    if ((await o.getAttribute('aria-selected')) === 'true') continue
    await o.click({ timeout: 1000 }).catch(() => {})
    picked++
  }
  if (picked) {
    await page.keyboard.press('Escape').catch(() => {})
    return picked
  }
  for (const role of ['treeitem', 'checkbox']) {
    const boxes = page.getByRole(role)
    const count = Math.min(await boxes.count().catch(() => 0), 400)
    for (let i = 0; i < count && picked < n; i++) {
      const b = boxes.nth(i)
      if (!(await b.isVisible().catch(() => false))) continue
      const name = (await b.getAttribute('aria-label').catch(() => null)) ?? (await b.evaluate((el) => el.closest('label')?.innerText ?? el.parentElement?.innerText ?? '').catch(() => ''))
      if (EXCLUDE.test(name || '')) continue
      const checked = await b.isChecked().catch(() => null)
      if (checked) continue
      if (!(await b.isEnabled().catch(() => false))) continue
      await b.click({ timeout: 1000 }).catch(() => {})
      picked++
    }
    if (picked) return picked
  }
  return picked
}

/** Waits until the network is quiet for a moment (the mocks answer in-page). */
async function settle(page, ms = 500) {
  await page.waitForLoadState('networkidle', { timeout: 4000 }).catch(() => {})
  await sleep(ms)
}

const LISTING_BUTTONS = /^(Buckets laden|Bereiche laden|Ordner laden|Spaces laden|Sites suchen|Site öffnen|Ordner anzeigen|Durchsuchen|Laden|Liste laden|Erneut laden)$/
const TEST_BUTTON = /^(Verbindung testen|Verbindung prüfen|Testen|Erneut testen|Erneut prüfen)$/

// ---------------------------------------------------------------------------------------------
// wizard flow
// ---------------------------------------------------------------------------------------------

async function gotoWizard(page, scenario) {
  await page.goto(`${ARGS.base}/libraries/new?mockScenario=${scenario}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
  await settle(page, 600)
}

async function pickType(page, type) {
  const t = TYPES[type]
  if (await clickByName(page, ['radio', 'button', 'option', 'tab', 'link', 'checkbox'], t.tile, { wait: 300 })) return true
  const el = await firstVisible(page.getByText(t.tile))
  if (el) {
    await el.click().catch(() => {})
    await sleep(300)
    return true
  }
  return false
}

async function next(page) {
  const ok = await clickButton(page, /^(Weiter|Prüfen|Weiter zur Prüfung|Zur Prüfung|Übersicht prüfen)$/, { wait: 700 })
  await settle(page, 200)
  return ok
}

async function alertTexts(page) {
  return page
    .evaluate(() => [...document.querySelectorAll('[role=alert], .MuiAlert-root, .Mui-error.MuiFormHelperText-root')].filter((e) => e.offsetParent !== null).map((e) => e.innerText.trim().slice(0, 140)).join(' / '))
    .catch(() => '')
}

async function headingText(page) {
  return page
    .evaluate(() => [...document.querySelectorAll('h1,h2')].filter((h) => h.offsetParent !== null).map((h) => h.innerText.trim()).join(' | '))
    .catch(() => '')
}

/** After picking the type: choose the connection (profile / own address) if the variant asks. */
async function chooseConnection(page, type) {
  const t = TYPES[type]
  if (t.profile) await clickByName(page, ['radio', 'button', 'option'], t.profile, { wait: 400 })
  if (t.ownAddress) await clickByName(page, ['radio', 'button'], /Eigene Adresse/, { wait: 400 })
  // a select/combobox for the profile
  if (t.profile) {
    const combo = await firstVisible(page.getByRole('combobox', { name: /Zugang/ }))
    if (combo) {
      await combo.click().catch(() => {})
      await clickByName(page, ['option'], t.profile, { wait: 300 })
    }
  }
}

/** Dropbox: confirm the service account and run the mock OAuth round trip back into the wizard. */
async function dropboxConsent(page) {
  await clickByName(page, ['checkbox'], /Dienstkonto/, { wait: 200 })
  const left = await clickButton(page, /^(Quelle verbinden|Verbinden|Mit Dropbox verbinden|Dropbox verbinden)$/, { wait: 300 })
  if (!left) return false
  await page.waitForURL(/\/libraries\/new|\/connections\/callback/, { timeout: 8000 }).catch(() => {})
  await page.waitForURL(/\/libraries\/new/, { timeout: 10000 }).catch(() => {})
  await settle(page, 1200)
  return true
}

async function triggerListings(page, { slow }) {
  // up to three listing levels (e.g. site -> library, folder levels)
  let clicked = false
  for (let round = 0; round < 2; round++) {
    const ok = await clickButton(page, LISTING_BUTTONS, { wait: slow ? 900 : 700 })
    if (!ok) break
    clicked = true
    if (slow) return clicked
    await settle(page, 300)
  }
  return clicked
}

const SELECT_BUTTON = /^(Auswählen …|Ändern)$/

/** Variant F: summary rows; „Umfang“ with the radio pair, inline checkboxes or the shared dialog. */
async function fillScopeRows(page, type, scenario, many, slow) {
  if (!(await page.locator('[data-row]').count().catch(() => 0))) return false
  await clickButton(page, /^Übernehmen$/, { wait: slow ? 600 : 900 })
  await clickButton(page, /^Ändern Umfang$/, { wait: 400 })
  const row = page.locator('[data-row$="-row-scope"]')
  if (type === 'filesystem') return true
  const some = await firstVisible(row.getByRole('radio', { name: /^Nur ausgewählte/ }))
  if (some && scenario !== 'empty') {
    await some.click().catch(() => {})
  }
  await sleep(slow ? 900 : 1100)
  if (slow) return true
  const inlineBoxes = row.getByRole('group').getByRole('checkbox')
  if ((await inlineBoxes.count().catch(() => 0)) > 0) {
    let picked = 0
    for (let i = 0; i < (await inlineBoxes.count()) && picked < many; i++) {
      const b = inlineBoxes.nth(i)
      if ((await b.isChecked().catch(() => true)) || !(await b.isEnabled().catch(() => false))) continue
      await b.click().catch(() => {})
      picked++
    }
    return true
  }
  const opener = await firstVisible(row.getByRole('button', { name: SELECT_BUTTON }))
  if (opener) await selectInDialog(page, type, scenario, opener)
  return true
}

/** Opens the selection dialog, picks there (drilling into SharePoint levels), applies. */
async function selectInDialog(page, type, scenario, opener) {
  const many = scenario === 'scale' ? 12 : 2
  await opener.click().catch(() => {})
  await settle(page, 400)
  const dialog = () => page.getByRole('dialog').last()
  if (scenario === 'failed' || scenario === 'empty') {
    await settle(page, 300)
    await shoot(page, 'wizard-select', type, scenario)
    await clickButton(page, /^Abbrechen$/, { wait: 400 })
    return
  }
  const open = () => firstVisible(dialog().getByRole('button', { name: /^„.*“ öffnen$/ }))
  if (type === 'sharepoint') {
    const site = await open()
    if (site) {
      await site.click().catch(() => {})
      await settle(page, 500)
    }
    if (scenario === 'scale') {
      await selectSome(page, 2)
      const lib = await open()
      if (lib) {
        await lib.click().catch(() => {})
        await settle(page, 400)
      }
    }
  }
  if (scenario === 'scale' && ['nextcloud', 'smb', 'dropbox', 's3'].includes(type)) {
    const deeper = await open()
    if (deeper) {
      await deeper.click().catch(() => {})
      await settle(page, 400)
    }
  }
  await selectSome(page, many)
  await sleep(200)
  await shoot(page, 'wizard-select', type, scenario)
  await clickButton(page, /^Übernehmen$/, { wait: 500 })
}

async function fillSource(page, type, scenario) {
  const slow = scenario === 'slow'
  const many = scenario === 'scale' ? 12 : 2
  switch (type) {
    case 'filesystem':
      await fillByLabel(page, [/^Verzeichnis/, /Verzeichnispfad/, /Pfad/], '/srv/opaa/sozialamt')
      await fillByLabel(page, [/Ausschlussmuster/, /Ausschl/], 'Archiv/**\n**/*.tmp')
      break
    case 'confluence':
      await fillByLabel(page, [/Adresse der Confluence/, /Adresse/, /URL/], 'https://rheinfurt.atlassian.net/wiki')
      await clickButton(page, /^(Edition erkennen|Adresse prüfen|Prüfen)$/, { wait: 800 })
      await settle(page, 200)
      await fillByLabel(page, [/E-Mail/], 'dienstkonto@rheinfurt.example')
      await fillByLabel(page, [/API-Token/, /Token/, /Zugangsschlüssel/], 'atl-token-123')
      break
    case 's3':
      await fillByLabel(page, [/Endpoint/, /Adresse/], 'https://minio.intern.example:9000')
      await fillByLabel(page, [/Access Key/, /Zugriffsschlüssel/, /Schlüssel-ID/], 'OPAAINDEXER')
      await fillByLabel(page, [/Secret Key/, /Geheimer Schlüssel/, /Secret/], 'geheim')
      break
    case 'gdrive': {
      const input = page.locator('input[type=file]').first()
      if (await input.count()) await input.setInputFiles(GDRIVE_KEY).catch(() => {})
      await sleep(400)
      break
    }
    case 'sharepoint':
      if (scenario === 'scale' || scenario === 'partial') {
        await fillByLabel(page, [/nach Sites suchen/, /Sites? suchen/, /Suche/], 't')
        await clickButton(page, /^Sites suchen$|^Suchen$/, { wait: slow ? 900 : 900 })
        await settle(page, 200)
        await clickByName(page, ['button', 'radio', 'option', 'treeitem', 'checkbox'], /^Rathaus$|^Team-Site 0|^Projekt 0/, { wait: 800 })
      } else {
        await fillByLabel(page, [/Adresse der Site/, /Site-Adresse/, /Adresse/], 'https://rheinfurt.sharepoint.com/sites/rathaus')
        await clickButton(page, /^(Site öffnen|Öffnen|Laden)$/, { wait: 900 })
      }
      await settle(page, 200)
      break
    case 'nextcloud':
      await fillByLabel(page, [/Adresse der Nextcloud/, /Adresse/], 'https://cloud.rheinfurt.example')
      await fillByLabel(page, [/^Benutzername/, /Kennung/, /Benutzer/], 'svc-opaa')
      await fillByLabel(page, [/App-Passwort/, /Passwort/], 'app-passwort')
      break
    case 'smb':
      await fillByLabel(page, [/Adresse der Freigabe/, /Adresse/], 'smb://dateiserver.rheinfurt.example/Ablage')
      await fillByLabel(page, [/^Benutzername/, /Kennung/, /Benutzer/], 'RHEINFURT\\svc-opaa')
      await fillByLabel(page, [/^Passwort/, /Passwort/], 'geheim')
      break
    case 'dropbox':
      break
  }
  if (await fillScopeRows(page, type, scenario, many, slow)) return
  // listings, then a selection, then the explicit test
  if (type === 'confluence') {
    await clickButton(page, TEST_BUTTON, { wait: slow ? 900 : 1000 })
    if (!slow) {
      await settle(page, 200)
      const combo = await firstVisible(page.getByRole('combobox', { name: /Space/ }))
      if (combo) {
        await combo.click().catch(() => {})
        await sleep(300)
      }
      await selectSome(page, many)
    }
    return
  }
  await triggerListings(page, { slow })
  if (slow) return
  if (type === 's3') {
    const combo = await firstVisible(page.getByRole('combobox', { name: /Bucket/ }))
    if (combo) {
      await combo.click().catch(() => {})
      await sleep(300)
    }
  }
  if (type === 'nextcloud' || type === 'smb' || type === 'dropbox') {
    // chip-style "Ordner … übernehmen" buttons of the current design
    for (let i = 0; i < many; i++) {
      if (!(await clickButton(page, /übernehmen$/, { wait: 60 }))) break
    }
  }
  await selectSome(page, many)
  if (type === 's3') {
    // a variant without a bucket listbox (or a refused listing): type the bucket by hand
    const bucket = await firstVisible(page.getByLabel(/^Bucket/))
    if (bucket && (await bucket.inputValue().catch(() => 'x')) === '') {
      await bucket.fill(scenario === 'scale' ? 'bucket-0001' : 'protokolle').catch(() => {})
      await page.keyboard.press('Escape').catch(() => {})
    }
  }
  if (type === 'sharepoint') {
    // open the folder level of the first chosen library and pick there too
    if (await clickButton(page, /^(Ordner wählen|Ordner von|Ordner auswählen|Öffnen)/, { wait: 900 })) {
      await settle(page, 200)
      if (scenario === 'scale') {
        for (let d = 0; d < 3; d++) await clickButton(page, /^(Öffnen|Unterordner von)/, { wait: 600 })
      }
      await selectSome(page, many)
      await clickButton(page, /^Fertig$/, { wait: 300 })
    }
  }
  await clickButton(page, TEST_BUTTON, { wait: 1200 })
  await settle(page, 200)
}

async function finishToReview(page, type) {
  // source step -> name -> (sharing) -> review / create
  for (let step = 0; step < 5; step++) {
    const h = await headingText(page)
    if (/Prüfen|Überprüfen|Zusammenfassung|Kontrolle/.test(h)) return true
    const name = await firstVisible(page.getByLabel(/^Name/))
    if (name && (await name.inputValue().catch(() => '')) === '') {
      await name.fill(`${type} UI-Lab`).catch(() => {})
    }
    const reviewHeading = await firstVisible(page.getByRole('heading', { name: /Prüfen|Überprüfen|Zusammenfassung/ }))
    if (reviewHeading) return true
    const advanced = await next(page)
    if (!advanced) return false
  }
  return /Prüfen|Überprüfen|Zusammenfassung/.test(await headingText(page))
}

// ---------------------------------------------------------------------------------------------
// shooting and metrics
// ---------------------------------------------------------------------------------------------

async function ensureAxe(page) {
  if (ARGS.noAxe) return false
  const has = await page.evaluate(() => typeof window.axe !== 'undefined').catch(() => false)
  if (!has) await page.addScriptTag({ content: AXE_SOURCE }).catch(() => {})
  return true
}

/** Grows the viewport so inner scroll containers (AppShell main, dialogs) fit, for __full. */
async function growForFull(page, width) {
  for (let i = 0; i < 4; i++) {
    const extra = await page.evaluate(() => {
      // with an open dialog only its own scroll area counts; the page behind stays as it is
      const dialog = [...document.querySelectorAll('[role=dialog]')].find((d) => d.offsetParent !== null || getComputedStyle(d).position === 'fixed')
      let max = 0
      if (dialog) {
        for (const el of [dialog, ...dialog.querySelectorAll('*')]) {
          if (!/(auto|scroll)/.test(getComputedStyle(el).overflowY)) continue
          max = Math.max(max, el.scrollHeight - el.clientHeight)
        }
        return max
      }
      for (const el of document.querySelectorAll('*')) {
        const cs = getComputedStyle(el)
        if (!/(auto|scroll)/.test(cs.overflowY)) continue
        const d = el.scrollHeight - el.clientHeight
        if (d > max) max = d
      }
      return Math.max(max, document.documentElement.scrollHeight - window.innerHeight)
    })
    if (extra <= 2) break
    const vp = page.viewportSize()
    await page.setViewportSize({ width, height: Math.min(vp.height + extra + 8, 16000) })
    await sleep(200)
  }
}

const PAGE_METRICS = () => {
  const main = document.querySelector('[role=dialog]:not([aria-hidden=true])') ?? document.querySelector('main') ?? document.body
  const visible = (el) => {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none'
  }
  const scrollers = [document.documentElement, document.querySelector('main')].filter(Boolean)
  const overflow = scrollers.some((el) => el.scrollWidth > el.clientWidth + 1)
  let wideest = null
  if (overflow) {
    const vw = document.documentElement.clientWidth
    for (const el of document.querySelectorAll('main *')) {
      const r = el.getBoundingClientRect()
      if (r.right > vw + 1 && visible(el)) {
        wideest = `${el.tagName.toLowerCase()}.${String(el.className).split(' ')[0]} right=${Math.round(r.right)}`
        break
      }
    }
  }
  // content height: the viewport plus the largest hidden overflow of a scroll container (dialog first)
  const dialogEl = [...document.querySelectorAll('[role=dialog]')].find(visible)
  let hiddenOverflow = document.documentElement.scrollHeight - window.innerHeight
  for (const el of dialogEl ? [dialogEl, ...dialogEl.querySelectorAll('*')] : document.querySelectorAll('*')) {
    if (!/(auto|scroll)/.test(getComputedStyle(el).overflowY)) continue
    hiddenOverflow = Math.max(hiddenOverflow, el.scrollHeight - el.clientHeight)
  }
  const docHeight = window.innerHeight + Math.max(0, hiddenOverflow)
  // text cut off without an ellipsis (overflow hidden/clip), e.g. a long compound at 320 px
  let clipped = 0
  for (const el of main.querySelectorAll('*')) {
    if (el.scrollWidth <= el.clientWidth + 1 || !el.clientWidth) continue
    const cs = getComputedStyle(el)
    if (!/(hidden|clip)/.test(cs.overflowX) || cs.textOverflow === 'ellipsis' || !visible(el)) continue
    if (!el.innerText || !el.innerText.trim()) continue
    clipped++
  }
  const SUBMIT = /^(Weiter|Bibliothek anlegen|Anlegen|Erstellen|Speichern|Übernehmen|Prüfen)$/
  const buttons = [...document.querySelectorAll('button, [role=button]')].filter(visible)
  const disabledSubmits = buttons.filter((b) => SUBMIT.test((b.innerText || '').trim()) && (b.disabled || b.getAttribute('aria-disabled') === 'true')).map((b) => b.innerText.trim())
  const alertEls = new Set([...document.querySelectorAll('[role=alert], .MuiAlert-root')].filter(visible))
  const alerts = [...alertEls].map((el) => {
    const m = /MuiAlert-(?:standard|outlined|filled|color)(Error|Warning|Info|Success)/.exec(el.className)
    return m ? m[1].toLowerCase() : el.getAttribute('role') === 'alert' ? 'role-alert' : 'unknown'
  })
  const severities = alerts.reduce((acc, s) => ((acc[s] = (acc[s] ?? 0) + 1), acc), {})
  const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), [contenteditable=true]'
  const focusable = [...document.querySelectorAll(FOCUSABLE)].filter((el) => visible(el) && el.tabIndex >= 0).length
  const sizes = new Set()
  const colors = new Set()
  const walker = document.createTreeWalker(main, NodeFilter.SHOW_TEXT)
  let words = 0
  while (walker.nextNode()) {
    const t = walker.currentNode
    const text = t.textContent.trim()
    if (!text) continue
    const el = t.parentElement
    if (!el || !visible(el)) continue
    const cs = getComputedStyle(el)
    sizes.add(cs.fontSize)
    colors.add(cs.color)
    words += text.split(/\s+/).filter(Boolean).length
  }
  const fieldErrors = [...main.querySelectorAll('[aria-invalid=true]')].filter(visible).length
  return {
    title: document.title,
    url: location.pathname + location.search,
    overflowX: overflow,
    overflowCulprit: wideest,
    docHeight,
    clippedText: clipped,
    disabledSubmits: disabledSubmits.length,
    disabledSubmitLabels: disabledSubmits,
    alertCount: alerts.length,
    alertSeverities: severities,
    invalidFields: fieldErrors,
    focusable,
    fontSizes: sizes.size,
    colors: colors.size,
    words,
    headings: [...main.querySelectorAll('h1,h2,h3')].filter(visible).map((h) => h.innerText.trim()).slice(0, 12),
  }
}

/** Scroll offsets of every scrolled element, to put the page back after the tab trace. */
async function saveScroll(page) {
  return page.evaluate(() => {
    const saved = []
    document.querySelectorAll('*').forEach((el, i) => {
      if (el.scrollTop || el.scrollLeft) saved.push([i, el.scrollTop, el.scrollLeft])
    })
    return { saved, x: window.scrollX, y: window.scrollY }
  })
}

async function restoreScroll(page, state) {
  await page.evaluate((state) => {
    const all = document.querySelectorAll('*')
    all.forEach((el) => {
      if (el.scrollTop) el.scrollTop = 0
    })
    for (const [i, top, left] of state.saved) {
      if (all[i]) {
        all[i].scrollTop = top
        all[i].scrollLeft = left
      }
    }
    window.scrollTo(state.x, state.y)
    document.activeElement?.blur?.()
  }, state)
}

/** The first 40 tab stops from the top of the page (or of the open dialog's focus trap). */
async function tabTrace(page) {
  const scroll = await saveScroll(page)
  await page.evaluate(() => {
    document.activeElement?.blur?.()
    const dialog = document.querySelector('[role=dialog]')
    if (!dialog) window.scrollTo(0, 0)
  })
  const stops = []
  let hidden = 0
  let obscured = 0
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press('Tab')
    const probe = () => page.evaluate(() => {
      const el = document.activeElement
      if (!el || el === document.body) return null
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      // opacity is no criterion: MUI radios, checkboxes and switches focus a transparent native input
      const isHidden = r.width < 2 || r.height < 2 || cs.visibility === 'hidden'
      let isObscured = false
      if (!isHidden) {
        const x = Math.min(Math.max(r.left + r.width / 2, 0), window.innerWidth - 1)
        const y = Math.min(Math.max(r.top + r.height / 2, 0), window.innerHeight - 1)
        const top = document.elementFromPoint(x, y)
        isObscured = !!top && !el.contains(top) && !top.contains(el) && !(el.labels && [...el.labels].some((l) => l.contains(top)))
        if (r.bottom < 0 || r.top > window.innerHeight) isObscured = true
      }
      const labelled = el.getAttribute('aria-labelledby')
      const name =
        el.getAttribute('aria-label') ||
        (labelled && labelled.split(' ').map((id) => document.getElementById(id)?.innerText ?? '').join(' ')) ||
        (el.labels && el.labels[0]?.innerText) ||
        el.getAttribute('placeholder') ||
        (el.innerText || el.value || '').trim()
      return {
        role: el.getAttribute('role') || el.tagName.toLowerCase() + (el.type ? `:${el.type}` : ''),
        name: String(name).replace(/\s+/g, ' ').slice(0, 60),
        hidden: isHidden,
        obscured: isObscured,
      }
    })
    let s = await probe()
    // a skip link or a field scrolled into view may still be moving: look again once
    if (s?.obscured) {
      await sleep(250)
      s = await probe()
    }
    if (!s) continue
    if (s.hidden) hidden++
    if (s.obscured) obscured++
    stops.push(s)
  }
  await restoreScroll(page, scroll).catch(() => {})
  return { tabStops: stops, focusHidden: hidden, focusObscured: obscured }
}

async function runAxe(page, contrastOnly) {
  if (!(await ensureAxe(page))) return null
  return page
    .evaluate(async (contrastOnly) => {
      const options = contrastOnly
        ? { runOnly: { type: 'rule', values: ['color-contrast'] }, resultTypes: ['violations'] }
        : {
            runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] },
            rules: { 'target-size': { enabled: true }, region: { enabled: false } },
            resultTypes: ['violations'],
          }
      const r = await window.axe.run(document, options)
      const violations = r.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, sample: v.nodes[0]?.target?.join(' ') }))
      const contrast = r.violations.find((v) => v.id === 'color-contrast')?.nodes.length ?? 0
      return { violations, contrastViolations: contrast }
    }, contrastOnly)
    .catch((e) => ({ error: String(e).slice(0, 200) }))
}

function record(line) {
  fs.appendFileSync(METRICS, JSON.stringify(line) + '\n')
}

let shotCount = 0

/** Shoots the current state in every scheme and width and records metrics per shot. */
async function shoot(page, area, type, scenario, extra = {}) {
  for (const scheme of ARGS.schemes) {
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' })
    await sleep(150)
    for (const width of ARGS.widths) {
      await page.setViewportSize({ width, height: HEIGHT })
      await sleep(250)
      const name = `${area}__${type}__${scenario}__${scheme}__${width}`
      const file = path.join(ARGS.out, `${name}.png`)
      await page.screenshot({ path: file }).catch((e) => log('  screenshot failed', e.message))
      const m = await page.evaluate(PAGE_METRICS).catch((e) => ({ error: String(e).slice(0, 200) }))
      const axe = await runAxe(page, scheme !== 'light')
      if (!ARGS.noFull) {
        await growForFull(page, width)
        await page.screenshot({ path: path.join(ARGS.out, `${name}__full.png`) }).catch(() => {})
        await page.setViewportSize({ width, height: HEIGHT })
        await sleep(150)
      }
      const trace = scheme === 'light' ? await tabTrace(page).catch(() => null) : null
      record({ shot: `${name}.png`, area, type, scenario, scheme, width, status: 'ok', ...extra, ...m, axe, ...(trace ?? {}) })
      shotCount++
    }
  }
  await page.emulateMedia({ colorScheme: ARGS.schemes[0] })
  await page.setViewportSize({ width: ARGS.widths[0], height: HEIGHT })
  log(`  shot ${area} ${type} ${scenario}`)
}

function notFound(area, type, scenario, reason) {
  for (const scheme of ARGS.schemes)
    for (const width of ARGS.widths)
      record({ shot: null, area, type, scenario, scheme, width, status: 'not-found', reason })
  log(`  not-found ${area} ${type} ${scenario}: ${reason}`)
}

// ---------------------------------------------------------------------------------------------
// flows
// ---------------------------------------------------------------------------------------------

async function wizardFlow(page, type, scenario, states) {
  await gotoWizard(page, scenario)
  if (!(await pickType(page, type))) return notFound('wizard-empty', type, scenario, 'type tile')
  await next(page)
  await chooseConnection(page, type)
  if (type === 'dropbox') {
    if (!(await dropboxConsent(page))) {
      log('  dropbox consent button not found')
    }
    await chooseConnection(page, type)
  }
  const onSource = /Quelle|Verbindung|einrichten/.test(await headingText(page))
  if (states.includes('wizard-empty')) {
    if (onSource) await shoot(page, 'wizard-empty', type, scenario)
    else notFound('wizard-empty', type, scenario, `heading: ${await headingText(page)}`)
  }
  if (states.includes('wizard-validation')) {
    await next(page)
    if (/Quelle|Verbindung|einrichten/.test(await headingText(page))) await shoot(page, 'wizard-validation', type, scenario)
    else {
      notFound('wizard-validation', type, scenario, 'empty form advanced')
      await clickButton(page, /^Zurück$/, { wait: 600 })
    }
  }
  await fillSource(page, type, scenario)
  if (states.includes('wizard-filled')) await shoot(page, 'wizard-filled', type, scenario)
  if (states.includes('review') && wantArea('review')) {
    if (scenario === 'slow') await settle(page, 3500)
    const reached = await finishToReview(page, type)
    if (reached) await shoot(page, 'review', type, scenario)
    else notFound('review', type, scenario, `heading: ${await headingText(page)}; alerts: ${await alertTexts(page)}`)
  }
}

async function openSourceTab(page, type, scenario) {
  const id = TYPES[type].library
  await page.goto(`${ARGS.base}/libraries/${id}?mockScenario=${scenario}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('main', { timeout: 15000 }).catch(() => {})
  await settle(page, 800)
  // under load the detail may still be loading: wait for its tabs before looking for „Quelle“
  await page.getByRole('tab').first().waitFor({ timeout: 8000 }).catch(() => {})
  const tab = await firstVisible(page.getByRole('tab', { name: /^Quelle/ }))
  if (tab) {
    await tab.click().catch(() => {})
    await settle(page, 600)
    return true
  }
  // a variant may show the source on its own route or section
  return !!(await firstVisible(page.getByRole('heading', { name: /Quelle|Anbindung|Verbindung/ })))
}

async function detailFlow(page, type, scenario, states) {
  const opened = await openSourceTab(page, type, scenario)
  if (states.includes('detail')) {
    if (opened) await shoot(page, 'detail', type, scenario)
    else notFound('detail', type, scenario, 'tab Quelle')
  }
  if (!states.includes('edit') || !wantArea('edit')) return
  if (!opened) return notFound('edit', type, scenario, 'tab Quelle')
  const edit = (await clickButton(page, /^Ändern Umfang$/, { wait: 900 })) || await clickButton(page, /^(Quellkonfiguration bearbeiten|Quelle bearbeiten|Anbindung bearbeiten|Verbindung bearbeiten|Bearbeiten|Ändern)$/, { wait: 900 })
  if (!edit) return notFound('edit', type, scenario, 'edit button')
  await settle(page, 300)
  // load the listing in the edit flow too, so the selection UI shows its real size
  await triggerListings(page, { slow: scenario === 'slow' })
  await sleep(800)
  await shoot(page, 'edit', type, scenario)
  // variant F: the stored selection, preselected in the shared dialog
  const opener = await firstVisible(page.locator('[data-row$="-row-scope"]').getByRole('button', { name: SELECT_BUTTON }))
  if (opener && (scenario === 'scale' || scenario === 'small')) {
    await opener.click().catch(() => {})
    await settle(page, 700)
    await shoot(page, 'edit-select', type, scenario)
  }
}

;(async () => {
  log(`base ${ARGS.base} -> ${ARGS.out}`)
  log(`types ${ARGS.types.join(',')} | scenarios ${ARGS.scenarios.join(',')} | schemes ${ARGS.schemes.join(',')} | widths ${ARGS.widths.join(',')}`)
  const browser = await chromium.launch()
  const context = await browser.newContext({
    viewport: { width: ARGS.widths[0], height: HEIGHT },
    locale: 'de-DE',
    reducedMotion: 'reduce',
    colorScheme: ARGS.schemes[0],
  })
  await context.addInitScript(() => {
    try {
      localStorage.setItem('opaa-ui-preferences', JSON.stringify({ state: { themeMode: 'system', sidebarWidth: 248 }, version: 0 }))
    } catch {
      /* ignore */
    }
  })
  const jobs = []
  for (const scenario of ARGS.scenarios) for (const type of ARGS.types) jobs.push({ scenario, type })
  async function worker() {
    const page = await context.newPage()
    page.on('pageerror', (e) => log('  PAGEERROR', e.message.slice(0, 160)))
    for (let job = jobs.shift(); job; job = jobs.shift()) {
      const { scenario, type } = job
      if (!TYPES[type]) {
        log(`unknown type ${type}`)
        continue
      }
      const states = statesFor(scenario).filter((s) => wantArea(s.split('-')[0]))
      log(`== ${type} / ${scenario}`)
      try {
        if (states.some((s) => s.startsWith('wizard') || s === 'review')) await wizardFlow(page, type, scenario, states)
      } catch (e) {
        log('  wizard flow error', e.message.split('\n')[0])
        record({ area: 'wizard', type, scenario, status: 'error', reason: e.message.split('\n')[0] })
      }
      try {
        if (states.includes('detail') || states.includes('edit')) await detailFlow(page, type, scenario, states)
      } catch (e) {
        log('  detail flow error', e.message.split('\n')[0])
        record({ area: 'detail', type, scenario, status: 'error', reason: e.message.split('\n')[0] })
      }
    }
    await page.close()
  }
  await Promise.all(Array.from({ length: Math.max(1, ARGS.concurrency) }, worker))
  await browser.close()
  const secs = ((Date.now() - T0) / 1000).toFixed(0)
  log(`done: ${shotCount} shots in ${secs}s`)
  fs.writeFileSync(path.join(ARGS.out, 'run.json'), JSON.stringify({ args: ARGS, shots: shotCount, seconds: Number(secs), finishedAt: new Date().toISOString() }, null, 2))
})()
