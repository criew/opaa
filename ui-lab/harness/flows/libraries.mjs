import fs from 'node:fs'
import path from 'node:path'
import { sleep } from '../lib/browser.mjs'
import { alertTexts, clickButton, clickByName, fillByLabel, firstVisible, headingText, selectSome, settle } from '../lib/ui.mjs'

/**
 * Source flows of the knowledge libraries. Per (type, scenario) the wizard step "Quelle
 * einrichten" (empty / validation / filled) and the review step, then the fixture library of the
 * type: detail tab "Quelle" and its edit flow. Areas: wizard, review, detail, edit.
 */

export const description = 'Wissensbibliotheken: Quellassistent, Prüfschritt, Detail und Bearbeiten je Quellart'
export const ALL_TYPES = ['filesystem', 'confluence', 'sharepoint', 'nextcloud', 'smb', 's3', 'gdrive', 'dropbox']
export const DEFAULT_SCENARIOS = ['small', 'scale', 'empty', 'slow', 'failed', 'partial']
export const QUICK_SCENARIOS = ['small', 'scale']
export const AREAS = ['wizard', 'review', 'detail', 'edit']

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

const LISTING_BUTTONS = /^(Buckets laden|Bereiche laden|Ordner laden|Spaces laden|Sites suchen|Site öffnen|Ordner anzeigen|Durchsuchen|Laden|Liste laden|Erneut laden)$/
const TEST_BUTTON = /^(Verbindung testen|Verbindung prüfen|Testen|Erneut testen|Erneut prüfen)$/
const ON_SOURCE_STEP = /Quelle|Verbindung|einrichten/
const REVIEW_HEADING = /Prüfen|Überprüfen|Zusammenfassung|Kontrolle/

/** States shot per scenario; the non-structural scenarios only need the loaded source step + detail. */
function statesFor(scenario) {
  if (scenario === 'small') return ['wizard-empty', 'wizard-validation', 'wizard-filled', 'review', 'detail', 'edit']
  if (scenario === 'default') return ['wizard-empty', 'wizard-filled', 'review', 'detail', 'edit']
  if (scenario === 'scale') return ['wizard-filled', 'review', 'detail', 'edit']
  if (scenario === 'failed') return ['wizard-filled', 'detail', 'edit']
  return ['wizard-filled', 'detail']
}

export function jobs({ types = ALL_TYPES, scenarios }) {
  const unknown = types.filter((t) => !TYPES[t])
  if (unknown.length) throw new Error(`unknown type(s) ${unknown.join(',')}; known: ${ALL_TYPES.join(',')}`)
  return scenarios.flatMap((scenario) => types.map((type) => ({ type, scenario })))
}

let gdriveKey = null

/** A throw-away service-account file for the Google Drive upload field. */
export function setup({ out }) {
  gdriveKey = path.join(out, '.fake-service-account.json')
  fs.writeFileSync(
    gdriveKey,
    JSON.stringify({
      type: 'service_account',
      project_id: 'rheinfurt-projekt',
      client_email: 'opaa-indexer@rheinfurt-projekt.iam.gserviceaccount.com',
      private_key_id: 'fake0123',
      private_key: '-----BEGIN PRIVATE KEY-----\nFAKE\n-----END PRIVATE KEY-----\n',
    }),
  )
}

async function next(page) {
  const ok = await clickButton(page, /^(Weiter|Prüfen|Weiter zur Prüfung|Zur Prüfung|Übersicht prüfen)$/, { wait: 700 })
  await settle(page, 200)
  return ok
}

export async function gotoWizard(page, base, scenario) {
  await page.goto(`${base}/libraries/new?mockScenario=${scenario}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
  await settle(page, 600)
}

export async function pickType(page, type) {
  const t = TYPES[type]
  if (await clickByName(page, ['radio', 'button', 'option', 'tab', 'link', 'checkbox'], t.tile, { wait: 300 })) return true
  const el = await firstVisible(page.getByText(t.tile))
  if (!el) return false
  await el.click().catch(() => {})
  await sleep(300)
  return true
}

/** After picking the type: choose the connection (profile / own address) if the variant asks. */
async function chooseConnection(page, type) {
  const t = TYPES[type]
  if (t.profile) await clickByName(page, ['radio', 'button', 'option'], t.profile, { wait: 400 })
  if (t.ownAddress) await clickByName(page, ['radio', 'button'], /Eigene Adresse/, { wait: 400 })
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
  let clicked = false
  for (let round = 0; round < 2; round++) {
    if (!(await clickButton(page, LISTING_BUTTONS, { wait: slow ? 900 : 700 }))) break
    clicked = true
    if (slow) return clicked
    await settle(page, 300)
  }
  return clicked
}

export async function fillSource(page, type, scenario) {
  const slow = scenario === 'slow'
  const many = scenario === 'scale' ? 12 : 2
  switch (type) {
    case 'filesystem':
      await fillByLabel(page, [/Verzeichnispfad/, /Pfad/], '/srv/opaa/sozialamt')
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
      if ((await input.count()) && gdriveKey) await input.setInputFiles(gdriveKey).catch(() => {})
      await sleep(400)
      break
    }
    case 'sharepoint':
      if (scenario === 'scale' || scenario === 'partial') {
        await fillByLabel(page, [/nach Sites suchen/, /Sites? suchen/, /Suche/], 't')
        await clickButton(page, /^Sites suchen$|^Suchen$/, { wait: 900 })
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
      await fillByLabel(page, [/Technischer Nutzer/, /Benutzer/, /Nutzer/], 'svc-opaa')
      await fillByLabel(page, [/App-Passwort/, /Passwort/], 'app-passwort')
      break
    case 'smb':
      await fillByLabel(page, [/Adresse der Freigabe/, /Adresse/], 'smb://dateiserver.rheinfurt.example/Ablage')
      await fillByLabel(page, [/Dienstkonto/, /Benutzer/, /Konto/], 'RHEINFURT\\svc-opaa')
      await fillByLabel(page, [/^Passwort/, /Passwort/], 'geheim')
      break
    case 'dropbox':
      break
  }
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
    for (let i = 0; i < many; i++) {
      if (!(await clickButton(page, /übernehmen$/, { wait: 60 }))) break
    }
  }
  await selectSome(page, many)
  if (type === 's3') {
    // without a bucket listbox (or with a refused listing) the bucket is typed by hand
    const bucket = await firstVisible(page.getByLabel(/^Bucket/))
    if (bucket && (await bucket.inputValue().catch(() => 'x')) === '') {
      await bucket.fill(scenario === 'scale' ? 'bucket-0001' : 'protokolle').catch(() => {})
      await page.keyboard.press('Escape').catch(() => {})
    }
  }
  if (type === 'sharepoint') {
    // the folder level of the first chosen library
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
  for (let step = 0; step < 5; step++) {
    if (REVIEW_HEADING.test(await headingText(page))) return true
    const name = await firstVisible(page.getByLabel(/^Name/))
    if (name && (await name.inputValue().catch(() => '')) === '') await name.fill(`${type} UI-Lab`).catch(() => {})
    if (!(await next(page))) return false
  }
  return REVIEW_HEADING.test(await headingText(page))
}

async function wizardFlow(page, { type, scenario }, ctx, states) {
  await gotoWizard(page, ctx.base, scenario)
  if (!(await pickType(page, type))) {
    for (const s of states.filter((s) => s.startsWith('wizard') || s === 'review')) ctx.notFound(s, 'type tile')
    return
  }
  await next(page)
  await chooseConnection(page, type)
  if (type === 'dropbox') {
    if (!(await dropboxConsent(page))) ctx.log('  dropbox consent button not found')
    await chooseConnection(page, type)
  }
  const onSource = ON_SOURCE_STEP.test(await headingText(page))
  if (states.includes('wizard-empty')) {
    if (onSource) await ctx.shoot('wizard-empty')
    else ctx.notFound('wizard-empty', `heading: ${await headingText(page)}`)
  }
  if (states.includes('wizard-validation')) {
    await next(page)
    if (ON_SOURCE_STEP.test(await headingText(page))) await ctx.shoot('wizard-validation')
    else {
      ctx.notFound('wizard-validation', 'empty form advanced')
      await clickButton(page, /^Zurück$/, { wait: 600 })
    }
  }
  await fillSource(page, type, scenario)
  if (states.includes('wizard-filled')) await ctx.shoot('wizard-filled')
  if (states.includes('review')) {
    if (scenario === 'slow') await settle(page, 3500)
    if (await finishToReview(page, type)) await ctx.shoot('review')
    else ctx.notFound('review', `heading: ${await headingText(page)}; alerts: ${await alertTexts(page)}`)
  }
}

export async function openSourceTab(page, base, type, scenario) {
  await page.goto(`${base}/libraries/${TYPES[type].library}?mockScenario=${scenario}`, { waitUntil: 'domcontentloaded' })
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
  return !!(await firstVisible(page.getByRole('heading', { name: /Quelle|Anbindung|Verbindung/ })))
}

async function detailFlow(page, { type, scenario }, ctx, states) {
  const opened = await openSourceTab(page, ctx.base, type, scenario)
  if (states.includes('detail')) {
    if (opened) await ctx.shoot('detail')
    else ctx.notFound('detail', 'tab Quelle')
  }
  if (!states.includes('edit')) return
  if (!opened) return ctx.notFound('edit', 'tab Quelle')
  const edit = await clickButton(page, /^(Quellkonfiguration bearbeiten|Quelle bearbeiten|Anbindung bearbeiten|Verbindung bearbeiten|Bearbeiten|Ändern)$/, { wait: 900 })
  if (!edit) return ctx.notFound('edit', 'edit button')
  await settle(page, 300)
  // the listing in the edit flow too, so the selection UI shows its real size
  await triggerListings(page, { slow: scenario === 'slow' })
  await ctx.shoot('edit')
}

/** ctx: { base, areas, shoot(area, extra?), notFound(area, reason), error(area, e), log } */
export async function run(page, job, ctx) {
  const states = statesFor(job.scenario).filter((s) => ctx.areas.includes(s.split('-')[0]))
  try {
    if (states.some((s) => s.startsWith('wizard') || s === 'review')) await wizardFlow(page, job, ctx, states)
  } catch (e) {
    ctx.error('wizard', e)
  }
  try {
    if (states.includes('detail') || states.includes('edit')) await detailFlow(page, job, ctx, states)
  } catch (e) {
    ctx.error('detail', e)
  }
}
