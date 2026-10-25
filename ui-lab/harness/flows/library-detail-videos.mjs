/**
 * Video matrix of the knowledge library detail page for record-flows.mjs: one flow per tab (plus
 * `kopf`, the head and the band above the tabs), each visiting the same tab for several source
 * types in a row, so the differences between them show. ok (small), viele (scale), fehler
 * (failed); `laeufe` opens the tab „Verlauf“, expands runs and scrolls through their details.
 */

export const TYPES = ['kopf', 'dokumente', 'quelle', 'laeufe', 'metadaten', 'freigaben', 'zuordnungen']
export const KINDS = { ok: 'small', viele: 'scale', fehler: 'failed' }

const LIBRARIES = {
  Upload: 'library-upload-rundschreiben',
  Dateisystem: 'library-filesystem-satzungen',
  RSS: 'library-rss-presse',
  Confluence: 'library-confluence-wiki',
  SharePoint: 'library-sharepoint-rathaus',
  Nextcloud: 'library-nextcloud-projekte',
  S3: 'library-s3-protokolle',
  Dropbox: 'library-dropbox-projekte',
}

const VISITS = {
  kopf: ['Upload', 'Dateisystem', 'RSS', 'Confluence', 'SharePoint', 'Nextcloud', 'S3', 'Dropbox'],
  dokumente: ['Upload', 'Confluence', 'Nextcloud'],
  quelle: ['Dateisystem', 'Confluence', 'SharePoint', 'S3'],
  laeufe: ['Confluence', 'Nextcloud', 'RSS'],
  metadaten: ['Upload', 'Confluence'],
  freigaben: ['Upload', 'SharePoint'],
  zuordnungen: ['Upload', 'Confluence'],
}
const TAB = { kopf: null, dokumente: null, quelle: 'quelle', laeufe: 'verlauf', metadaten: 'metadaten', freigaben: 'freigaben', zuordnungen: 'zuordnungen' }

async function scroll(h, steps, dy = 360) {
  await h.page.mouse.move(700, 500)
  for (let i = 0; i < steps; i++) {
    await h.page.mouse.wheel(0, dy)
    await h.pause(350)
  }
}

async function documents(h, name, kind) {
  await scroll(h, kind === 'viele' ? 4 : 2)
  if (name === 'Upload') {
    const folder = h.page.getByRole('link', { name: /^Ordner 001/ }).or(h.page.getByRole('button', { name: /^Ordner 001/ }))
    if (await h.visible(folder)) await h.click(folder, 'Ordner 001')
  }
  if (kind === 'viele') {
    const search = h.page.getByPlaceholder(/Dateiname suchen|durchsuchen/)
    await h.type(search, '007', 'Suche')
    await h.pause(900)
  }
  if (kind === 'fehler' && name === 'Upload') {
    const input = h.page.locator('input[type=file]').first()
    if (await input.count()) {
      await h.caption('Upload: Kontingent erschöpft')
      await input.setInputFiles({ name: 'Rundschreiben 2026-14.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4') }).catch(() => {})
      await h.pause(1500)
    }
  }
}

async function runs(h, kind) {
  const rows = h.page.locator('#library-tabpanel-verlauf ul[aria-label="Läufe"] > li > button')
  await rows.first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {})
  const count = await rows.count().catch(() => 0)
  if (!count) return
  await h.click(rows.first(), 'neuester Lauf')
  await scroll(h, kind === 'viele' ? 6 : 2, 480)
  const examples = h.page.getByRole('button', { name: /^Alle \d+/ })
  if (await h.visible(examples)) {
    await h.click(examples.first(), 'alle Beispiele')
    await h.pause(1200)
    await h.page.keyboard.press('Escape').catch(() => {})
    await h.pause(500)
  }
  if (count > 3) {
    await h.page.mouse.wheel(0, -4000)
    await h.pause(400)
    await h.click(rows.nth(3), 'vierter Lauf')
    await scroll(h, 2)
  }
}

async function source(h, kind) {
  await scroll(h, kind === 'viele' ? 3 : 1)
  const change = h.page.getByRole('button', { name: /^Ändern/ }).nth(1)
  if (await h.visible(change)) {
    await h.click(change, 'Umfang ändern')
    await h.pause(1200)
    await h.page.keyboard.press('Escape').catch(() => {})
    await h.pause(500)
  }
}

export async function run(h, { type, kind, scenario }) {
  for (const name of VISITS[type]) {
    const query = new URLSearchParams({ mockScenario: scenario })
    if (TAB[type]) query.set('tab', TAB[type])
    await h.goto(`/libraries/${LIBRARIES[name]}?${query}`)
    // the lazily loaded page is ready once its tabs are there
    await h.page.getByRole('tab').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => h.log('  missing: tabs'))
    await h.caption(`${name} · ${type === 'kopf' ? 'Kopf' : type}`)
    await h.pause(type === 'kopf' ? 1400 : 700)
    if (type === 'dokumente') await documents(h, name, kind)
    else if (type === 'laeufe') await runs(h, kind)
    else if (type === 'quelle') await source(h, kind)
    else if (type !== 'kopf') await scroll(h, kind === 'viele' ? 5 : 1)
    await h.pause(600)
  }
  await h.caption('')
}
