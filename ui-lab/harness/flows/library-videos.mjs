import fs from 'node:fs'
import path from 'node:path'

/**
 * Video matrix of the knowledge library sources for record-flows.mjs: one flow per source type,
 * played in three kinds. ok (small): set up the source, list, pick, test, go on to the next step.
 * viele (scale): the same with large listings, more picks and scrolling. fehler (failed): an empty
 * submit first, then the refused listing or connection test. Labels follow the UI on main; a
 * variant with other words or another structure brings its own module of the same shape.
 */

export const TYPES = ['filesystem', 'confluence', 'sharepoint', 'nextcloud', 'smb', 's3', 'gdrive', 'dropbox']
export const KINDS = { ok: 'small', viele: 'scale', fehler: 'failed' }

const TILES = {
  filesystem: /^Dateisystem/,
  confluence: /^Confluence/,
  sharepoint: /^SharePoint/,
  nextcloud: /^Nextcloud/,
  smb: /^Windows-Dateifreigabe|^SMB/,
  s3: /^S3/,
  gdrive: /^Google Drive/,
  dropbox: /^Dropbox/,
}
const CAPTIONS = { ok: 'wenige Einträge', viele: 'viele Einträge', fehler: 'Fehlerfall' }
const LISTING = /^(Buckets laden|Bereiche laden|Ordner laden|Spaces laden|Site öffnen|Sites suchen)$/
const PICK_EXCLUDE = /sofort|Indizierung|bestätige|Dienstkonto|Path-Style|Pfad-Stil|Diagnose|Benachrichtig/

const button = (h, name) => h.page.getByRole('button', { name })
const field = (h, label) => h.page.getByLabel(label)

/** Ticks up to `n` visible, unchecked source objects with the pointer: options first, then checkboxes. */
async function pick(h, n) {
  let picked = 0
  for (const role of ['option', 'checkbox']) {
    const items = h.page.getByRole(role)
    const count = Math.min(await items.count().catch(() => 0), 60)
    for (let i = 0; i < count && picked < n; i++) {
      const item = items.nth(i)
      if (!(await item.isVisible().catch(() => false))) continue
      const name = (await item.getAttribute('aria-label').catch(() => null)) ?? (await item.evaluate((el) => el.closest('label')?.innerText ?? '').catch(() => ''))
      if (PICK_EXCLUDE.test(name)) continue
      if (role === 'checkbox' && (await item.isChecked().catch(() => true))) continue
      if (await h.click(item, `${role} ${name}`)) picked++
    }
    if (picked) {
      if (role === 'option') await h.page.keyboard.press('Escape').catch(() => {})
      return picked
    }
  }
  return picked
}

async function start(h, type, scenario, kind) {
  await h.goto(`/libraries/new?mockScenario=${scenario}`)
  await h.caption(`${TILES[type].source.replace(/[\^|$\\]/g, ' ').trim().split(' ')[0]}: ${CAPTIONS[kind]}`)
  await h.click(h.page.getByRole('radio', { name: TILES[type] }).or(button(h, TILES[type])), 'Kachel')
  await h.click(button(h, /^Weiter$/), 'Weiter')
  await h.caption('')
}

/** Connection fields of the type; Dropbox runs the mock consent round trip. */
async function connect(h, type, kind) {
  const many = kind === 'viele'
  switch (type) {
    case 'filesystem':
      await h.type(field(h, /Verzeichnispfad|Verzeichnis|Pfad/), many ? '/srv/opaa/grundstuecksverkehrsgenehmigungsakte/fachbereich-soziales' : '/srv/opaa/sozialamt', 'Pfad')
      break
    case 'confluence':
      await h.type(field(h, /Adresse/), 'https://rheinfurt.atlassian.net/wiki', 'Adresse')
      await h.click(button(h, /^(Edition erkennen|Adresse prüfen)$/), 'Edition erkennen')
      await h.type(field(h, /E-Mail/), 'dienstkonto@rheinfurt.example', 'E-Mail')
      await h.type(field(h, /API-Token/), 'atl-token-123', 'API-Token')
      break
    case 'sharepoint':
      await h.click(h.page.getByRole('radio', { name: /Microsoft 365 Rheinfurt/ }).or(button(h, /Microsoft 365 Rheinfurt/)), 'Zugang')
      if (many) {
        await h.type(field(h, /nach Sites suchen|Sites suchen/), 'Rat', 'Site-Suche')
      } else {
        await h.type(field(h, /Adresse der Site/), 'https://rheinfurt.sharepoint.com/sites/rathaus', 'Adresse der Site')
      }
      break
    case 'nextcloud':
      await h.click(h.page.getByRole('radio', { name: /Eigene Adresse/ }).or(button(h, /Eigene Adresse/)), 'Eigene Adresse')
      await h.type(field(h, /Adresse der Nextcloud|Adresse/), 'https://cloud.rheinfurt.example', 'Adresse')
      await h.type(field(h, /Technischer Nutzer|Benutzername/), 'svc-opaa', 'Nutzer')
      await h.type(field(h, /App-Passwort/), 'app-passwort', 'App-Passwort')
      break
    case 'smb':
      await h.type(field(h, /Adresse der Freigabe|Adresse/), 'smb://dateiserver.rheinfurt.example/Ablage', 'Adresse')
      await h.type(field(h, /Dienstkonto|Benutzername/), 'RHEINFURT\\svc-opaa', 'Konto')
      await h.type(field(h, /^Passwort/), 'geheim', 'Passwort')
      break
    case 's3':
      await h.type(field(h, /Endpoint/), 'https://minio.intern.example:9000', 'Endpoint')
      await h.type(field(h, /Access Key/), 'OPAAINDEXER', 'Access Key')
      await h.type(field(h, /Secret Key/), kind === 'fehler' ? 'falsch' : 'geheim', 'Secret Key')
      break
    case 'gdrive': {
      const key = path.join(h.out, '.fake-service-account.json')
      fs.writeFileSync(key, JSON.stringify({ type: 'service_account', client_email: 'opaa-indexer@rheinfurt-projekt.iam.gserviceaccount.com', private_key: 'FAKE' }))
      await h.click(button(h, /Datei wählen|Schlüsseldatei/), 'Schlüsseldatei')
      await h.page.locator('input[type=file]').first().setInputFiles(key).catch(() => h.log('  missing: file input'))
      await h.pause()
      break
    }
    case 'dropbox':
      await h.click(h.page.getByRole('radio', { name: /Dropbox Rheinfurt/ }).or(button(h, /Dropbox Rheinfurt/)), 'Zugang')
      await h.click(h.page.getByRole('checkbox', { name: /Dienstkonto/ }), 'Dienstkonto')
      if (await h.click(button(h, /^(Quelle verbinden|Verbinden)$/), 'Quelle verbinden')) {
        await h.page.waitForURL(/\/libraries\/new/, { timeout: 15000 }).catch(() => {})
        await h.pause(1500)
      }
      break
  }
}

/** Lists, picks and tests; `viele` picks more and scrolls through the long list. */
async function scope(h, type, kind) {
  const many = kind === 'viele'
  if (type !== 'filesystem') {
    if (type === 'sharepoint' && many) {
      await h.click(button(h, /^Sites suchen$/), 'Sites suchen')
      const site = /^Rathaus$|^Team-Site 0|^Projekt 0/
      const hit = ['button', 'radio', 'option', 'treeitem'].map((role) => h.page.getByRole(role, { name: site })).reduce((a, b) => a.or(b))
      // a variant that lists the libraries of every hit at once has nothing to open here
      if (await h.visible(hit)) await h.click(hit, 'Site')
    } else {
      await h.click(button(h, LISTING), 'Auflistung')
    }
    await h.pause(800)
    if (type === 's3') await h.click(h.page.getByRole('combobox', { name: /Bucket/ }), 'Bucket-Liste')
    if (type === 'confluence') await h.click(h.page.getByRole('combobox', { name: /Space/ }), 'Space-Liste')
    if (many) {
      await h.page.mouse.wheel(0, 900)
      await h.pause()
      await h.page.mouse.wheel(0, -600)
    }
    await pick(h, many ? 6 : 2)
    const folders = button(h, /^(Ordner wählen|Ordner von|Ordner auswählen)/)
    if (type === 'sharepoint' && (await h.visible(folders, 800)) && (await h.click(folders, 'Ordner'))) {
      await pick(h, many ? 4 : 1)
      await h.click(button(h, /^Fertig$/), 'Fertig')
    }
  }
  await h.click(button(h, /^(Verbindung testen|Erneut testen)$/), 'Verbindung testen')
  await h.pause(1200)
}

export async function run(h, { type, kind, scenario }) {
  await start(h, type, scenario, kind)
  if (kind === 'fehler') {
    // the empty submit shows how the step reports missing input, then the refused source
    await h.click(button(h, /^Weiter$/), 'Weiter (leer)')
    await h.pause(1200)
  }
  await connect(h, type, kind)
  await scope(h, type, kind)
  if (kind === 'fehler') {
    await h.pause(1500)
    return
  }
  await h.click(button(h, /^Weiter$/), 'Weiter')
  await h.type(field(h, /^Name/), `${type} ${kind === 'viele' ? 'Gesamtbestand' : 'Projektablage'}`, 'Name')
  await h.click(button(h, /^Weiter$/), 'Weiter')
  await h.pause(1500)
}
