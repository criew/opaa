/**
 * Video matrix of variant F (#3084): per connector three click paths through the source setup.
 *
 *   ok      small:  wizard to „Prüfen“, create, then the Reiter „Quelle“ with one „Ändern“
 *   viele   scale:  the selection with many entries (levels, search, „Alle n auswählen“) to
 *                   „Prüfen“, then editing the fixture library with its stored selection
 *   fehler  failed: error summary, failing test or listing, correction or retry
 *
 * node record-flows.mjs --flows flows/f-videos.mjs --pause 600 --out <dir>
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const TYPES = {
  filesystem: { file: 'dateisystem', tile: /^Dateisystem/, library: 'library-filesystem-satzungen' },
  confluence: { file: 'confluence', tile: /^Confluence/, library: 'library-confluence-wiki' },
  sharepoint: { file: 'sharepoint', tile: /^SharePoint/, library: 'library-sharepoint-rathaus' },
  nextcloud: { file: 'nextcloud', tile: /^Nextcloud/, library: 'library-nextcloud-projekte' },
  smb: { file: 'smb', tile: /^Windows-Dateifreigabe/, library: 'library-smb-ablage' },
  s3: { file: 's3', tile: /^S3/, library: 'library-s3-protokolle' },
  gdrive: { file: 'googledrive', tile: /^Google Drive/, library: 'library-gdrive-projekte' },
  dropbox: { file: 'dropbox', tile: /^Dropbox/, library: 'library-dropbox-projekte' },
}

const GDRIVE_KEY = path.join(os.tmpdir(), 'ui-lab-fake-service-account.json')
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

const btn = (h, name, scope = h.page) => scope.getByRole('button', { name, exact: typeof name === 'string' })
const field = (h, label, scope = h.page) => scope.getByLabel(label, { exact: typeof label === 'string' })
const scopeRow = (h) => h.page.locator('[data-row$="-row-scope"]')
const dialog = (h) => h.page.getByRole('dialog').last()
const visible = async (locator, timeout = 1500) => {
  try {
    await locator.first().waitFor({ state: 'visible', timeout })
    return true
  } catch {
    return false
  }
}

async function start(h, type, scenario) {
  await h.goto(`/libraries/new?mockScenario=${scenario}`)
  await h.pause(600)
  await h.click(h.page.getByRole('radio', { name: TYPES[type].tile }), 'Kachel')
  await h.click(btn(h, 'Weiter'), 'Weiter')
}

async function connection(h, type, path = '/srv/opaa/sozialamt') {
  const { page } = h
  switch (type) {
    case 'filesystem':
      await h.type(field(h, 'Verzeichnis'), path, 'Verzeichnis')
      break
    case 'confluence':
      await h.type(field(h, 'Adresse'), 'https://rheinfurt.atlassian.net/wiki', 'Adresse')
      await h.pause(1000)
      await h.type(field(h, 'E-Mail-Adresse'), 'dienstkonto@rheinfurt.example', 'E-Mail')
      await h.type(field(h, 'API-Token'), 'atl-token-123', 'API-Token')
      break
    case 'sharepoint':
      await h.click(page.getByRole('radio', { name: /Microsoft 365 Rheinfurt/ }), 'Zugang')
      break
    case 'nextcloud':
      await h.click(page.getByRole('radio', { name: /^Eigene Adresse/ }), 'Eigene Adresse')
      await h.type(field(h, 'Adresse'), 'https://cloud.rheinfurt.example', 'Adresse')
      await h.type(field(h, 'Benutzername'), 'svc-opaa', 'Benutzername')
      await h.type(field(h, 'App-Passwort'), 'app-passwort', 'App-Passwort')
      break
    case 'smb':
      await h.type(field(h, 'Adresse'), 'smb://dateiserver.rheinfurt.example/Ablage', 'Adresse')
      await h.type(field(h, 'Benutzername'), 'RHEINFURT\\svc-opaa', 'Benutzername')
      await h.type(field(h, 'Passwort'), 'geheim', 'Passwort')
      break
    case 's3':
      await h.type(field(h, 'Endpoint'), 'https://minio.intern.example:9000', 'Endpoint')
      await h.type(field(h, 'Access Key'), 'OPAAINDEXER', 'Access Key')
      await h.type(field(h, 'Secret Key'), 'geheim', 'Secret Key')
      break
    case 'gdrive':
      await page.locator('input[type=file]').first().setInputFiles(GDRIVE_KEY).catch(() => {})
      await h.pause()
      break
    case 'dropbox':
      await h.click(page.getByRole('checkbox', { name: /Dienstkonto/ }), 'Dienstkonto')
      await h.click(btn(h, 'Quelle verbinden'), 'Quelle verbinden')
      await page.waitForURL(/\/libraries\/new/, { timeout: 15000 }).catch(() => {})
      await page.waitForSelector('main').catch(() => {})
      await h.pause(1500)
      break
  }
}

/** „Ändern Umfang“: opens the row, for SharePoint straight into the dialog. */
async function openScope(h) {
  if (await visible(scopeRow(h).getByRole('radio'), 600)) return
  if (await visible(scopeRow(h).getByRole('button', { name: /auswählen …$/ }), 600)) return
  await h.click(btn(h, 'Ändern Umfang'), 'Ändern Umfang')
  await h.pause(800)
}

async function onlySelected(h) {
  const some = scopeRow(h).getByRole('radio', { name: /^Nur ausgewählte/ })
  if (await visible(some, 800)) {
    await h.click(some, 'Nur ausgewählte')
    await h.pause(1000)
  }
}

async function toReview(h, name) {
  await h.click(btn(h, 'Weiter'), 'Weiter')
  await h.pause(800)
  await h.type(field(h, /^Name/), name, 'Name')
  for (let i = 0; i < 3; i++) {
    if (await visible(h.page.getByRole('tab', { name: /Prüfen/, selected: true }), 400)) break
    if (await visible(btn(h, 'Bibliothek anlegen'), 400)) break
    await h.click(btn(h, 'Weiter'), 'Weiter')
  }
  await h.pause(1500)
}

async function pickAllHits(h, d, term) {
  await h.type(d.getByRole('searchbox'), term, 'Suche')
  await h.pause(900)
  await h.click(d.getByText(/^Alle .* auswählen$/), 'Alle n auswählen')
}

// ---- ok ------------------------------------------------------------------------------------------

async function ok(h, type) {
  await start(h, type, 'small')
  await connection(h, type)
  await openScope(h)
  const row = scopeRow(h)
  if (type === 'filesystem') {
    await h.type(field(h, /^Ausschlussmuster/), 'Archiv/**', 'Ausschlussmuster')
  } else if (type === 'sharepoint') {
    const d = dialog(h)
    await h.pause(800)
    await h.click(d.getByRole('button', { name: '„Rathaus“ öffnen' }), 'Rathaus öffnen')
    await h.click(d.getByRole('checkbox', { name: 'Dokumente' }), 'Dokumente')
    await h.click(btn(h, 'Übernehmen', d), 'Übernehmen')
  } else {
    await onlySelected(h)
    const boxes = row.getByRole('group').getByRole('checkbox')
    await h.click(boxes.nth(0), 'erste Checkbox')
    if ((await boxes.count()) > 1) await h.click(boxes.nth(1), 'zweite Checkbox')
  }
  await toReview(h, 'Projektablage')
  await h.click(btn(h, 'Bibliothek anlegen'), 'Bibliothek anlegen')
  await h.page.waitForURL(/\/libraries\/(?!new)/, { timeout: 10000 }).catch(() => {})
  await h.pause(1500)
  await h.click(h.page.getByRole('tab', { name: /^Quelle/ }), 'Reiter Quelle')
  await h.click(btn(h, 'Ändern Zeitplan'), 'Ändern Zeitplan')
  const schedule = h.page.locator('[data-row$="-row-schedule"]')
  if (await h.click(schedule.getByRole('combobox').first(), 'Häufigkeit')) {
    await h.click(h.page.getByRole('option', { name: /Wöchentlich/ }), 'Wöchentlich')
  }
  await h.click(btn(h, 'Speichern', schedule), 'Speichern')
  await h.pause(2000)
}

// ---- viele ---------------------------------------------------------------------------------------

async function viele(h, type) {
  await start(h, type, 'scale')
  await connection(
    h,
    type,
    '/srv/opaa/grundstuecksverkehrsgenehmigungsakte/fachbereich-soziales-jugend-und-familie/eingliederungshilfe',
  )
  await openScope(h)
  if (type === 'filesystem') {
    await h.type(
      field(h, /^Ausschlussmuster/),
      'Archiv/**\n**/Entwurf_*/**\n**/*.tmp\nFachbereich-Soziales/Altakten-2009-2014/**',
      'Ausschlussmuster',
    )
  } else {
    if (type !== 'sharepoint') {
      await onlySelected(h)
      await h.click(scopeRow(h).getByRole('button', { name: /auswählen …$/ }), 'auswählen …')
    }
    const d = dialog(h)
    await h.pause(1000)
    const open = (name) => d.getByRole('button', { name: `„${name}“ öffnen` })
    if (type === 'confluence') await pickAllHits(h, d, 'Bauamt')
    else if (type === 'gdrive') await pickAllHits(h, d, 'Ordner 01')
    else if (type === 'sharepoint') {
      await h.type(d.getByRole('searchbox'), 'Rathaus', 'Site suchen')
      await h.pause(1000)
      await h.click(open('Rathaus'), 'Rathaus öffnen')
      await h.click(d.getByRole('checkbox', { name: 'Dokumentbibliothek 002' }), 'Bibliothek 002')
      await h.click(open('Dokumente'), 'Dokumente öffnen')
      await pickAllHits(h, d, 'Ordner 00')
      await h.click(d.getByRole('navigation').getByRole('button', { name: 'Rathaus' }), 'zurück')
    } else if (type === 's3') {
      await h.click(open('bucket-0001'), 'bucket-0001 öffnen')
      await pickAllHits(h, d, 'ordner-00')
      await h.click(d.getByRole('navigation').getByRole('button', { name: 'Buckets' }), 'zurück')
    } else {
      await h.click(open('Ordner 001'), 'Ordner 001 öffnen')
      await pickAllHits(h, d, 'Vorgang 00')
      await h.click(d.getByRole('navigation').getByRole('button').first(), 'zurück')
    }
    await h.pause(800)
    await h.click(btn(h, 'Übernehmen', d), 'Übernehmen')
  }
  await toReview(h, 'Gesamtbestand')
  // the stored library of the type: its selection is preselected
  await h.goto(`/libraries/${TYPES[type].library}?mockScenario=scale`)
  await h.click(h.page.getByRole('tab', { name: /^Quelle/ }), 'Reiter Quelle')
  await h.click(btn(h, 'Ändern Umfang'), 'Ändern Umfang')
  if (type !== 'filesystem' && type !== 'sharepoint') {
    await h.click(scopeRow(h).getByRole('button', { name: /auswählen …$/ }), 'auswählen …')
  }
  if (type !== 'filesystem') {
    await h.pause(2500)
    await h.click(btn(h, 'Abbrechen', dialog(h)), 'Abbrechen')
  }
  await h.pause(800)
}

// ---- fehler --------------------------------------------------------------------------------------

const MANUAL = {
  s3: 'protokolle/2026/',
  gdrive: '1AbCdEfGhIjKlMnOp',
  nextcloud: '/Projekte/2026',
  smb: '/Projekte/2026',
  dropbox: '/Projekte/2026',
  sharepoint: 'https://rheinfurt.sharepoint.com/sites/rathaus',
}

async function fehler(h, type) {
  const { page } = h
  await start(h, type, 'failed')
  if (type === 'dropbox' || type === 'sharepoint') {
    await connection(h, type)
    await h.click(btn(h, 'Weiter'), 'Weiter (leer)')
    if (type === 'sharepoint' && (await visible(dialog(h), 800))) await h.click(btn(h, 'Abbrechen', dialog(h)), 'Abbrechen')
  } else {
    await h.click(btn(h, 'Weiter'), 'Weiter (leer)')
    await h.pause(800)
    await h.click(page.getByRole('alert').getByRole('button').first(), 'Eintrag der Fehlerzusammenfassung')
    await connection(h, type)
  }
  if (!(await visible(btn(h, 'Verbindung testen'), 800))) await h.click(btn(h, 'Ändern Verbindung'), 'Ändern Verbindung')
  await h.click(btn(h, 'Verbindung testen'), 'Verbindung testen')
  await h.pause(1800)
  await h.click(btn(h, 'Trotzdem fortfahren'), 'Trotzdem fortfahren')
  if (type === 'filesystem') {
    await h.click(btn(h, 'Ändern Umfang'), 'Ändern Umfang')
    await h.type(field(h, /^Ausschlussmuster/), 'Archiv/**', 'Ausschlussmuster')
  } else {
    await openScope(h)
    if (type !== 'sharepoint') {
      await onlySelected(h)
      await h.click(btn(h, 'Erneut laden', scopeRow(h)), 'Erneut laden')
      await h.pause(1200)
    }
    if (MANUAL[type]) {
      if (type !== 'sharepoint') await h.click(btn(h, 'Von Hand eintragen …', scopeRow(h)), 'Von Hand eintragen')
      const d = dialog(h)
      await h.pause(1000)
      if (!(await visible(d.locator('#selection-manual'), 600))) {
        await h.click(d.getByRole('button', { name: 'Von Hand eintragen …' }), 'Von Hand eintragen')
      }
      await h.type(d.locator('#selection-manual'), MANUAL[type], 'Eingabe von Hand')
      await h.click(d.getByRole('button', { name: /^(Hinzufügen|Öffnen)$/ }), 'Hinzufügen')
      await h.pause(1200)
      await h.click(btn(h, type === 'sharepoint' ? 'Abbrechen' : 'Übernehmen', d), 'Dialog schließen')
    }
  }
  await h.click(btn(h, 'Weiter'), 'Weiter')
  await h.pause(2000)
}

const FLOWS = { ok: [ok, 'small'], viele: [viele, 'scale'], fehler: [fehler, 'failed'] }

export const videos = Object.keys(TYPES).flatMap((type) =>
  Object.entries(FLOWS).map(([kind, [run, scenario]]) => ({
    name: `${TYPES[type].file}__${kind}`,
    type,
    scenario,
    run: (h) => run(h, type),
  })),
)
