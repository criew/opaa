import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/**
 * Video matrix of variant C of the library detail page (#3084): one click path per tab, played in
 * three kinds - ok (small), viele (scale), fehler (failed). Each tab uses the library type that
 * shows it best; `abschluss` opens the page as the wizard hands it over (`?justCreated=1`).
 *
 *   node record-flows.mjs --flows flows/library-detail-c-videos.mjs --out <dir>
 */

export const TYPES = ['dokumente', 'quelle', 'metadaten', 'freigaben', 'zuordnungen', 'upload', 'abschluss']
export const KINDS = { ok: 'small', viele: 'scale', fehler: 'failed' }

const LIBRARY = {
  dokumente: 'library-confluence-wiki',
  quelle: 'library-confluence-wiki',
  metadaten: 'library-smb-ablage',
  freigaben: 'library-sharepoint-rathaus',
  zuordnungen: 'library-s3-protokolle',
  upload: 'library-upload-rundschreiben',
  abschluss: 'library-confluence-wiki',
}
const CAPTION = {
  dokumente: 'Dokumente',
  quelle: 'Quelle und Läufe',
  metadaten: 'Metadaten',
  freigaben: 'Freigaben',
  zuordnungen: 'Zuordnungen',
  upload: 'Upload',
  abschluss: 'Nach dem Anlegen',
}
const KIND_CAPTION = { ok: 'wenige Einträge', viele: 'viele Einträge', fehler: 'Fehlerfall' }

const button = (h, name) => h.page.getByRole('button', { name })
const link = (h, name) => h.page.getByRole('link', { name })
const tab = (h, name) => h.page.getByRole('tab', { name })
const steckbrief = (h) => h.page.getByRole('complementary', { name: 'Steckbrief' })

const FILES = (() => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ui-lab-c-upload-'))
  return [
    ['Rundschreiben 2026-14.pdf', '%PDF-1.4 Rundschreiben'],
    ['Datenbank Altbestand.mdb', 'binary'],
  ].map(([name, content]) => {
    const file = path.join(dir, name)
    fs.writeFileSync(file, content)
    return file
  })
})()

async function open(h, type, scenario, kind, extra = '') {
  await h.goto(`/libraries/${LIBRARY[type]}?mockScenario=${scenario}${extra}`)
  await h.page.getByRole('tab').first().waitFor({ timeout: 20000 }).catch(() => {})
  await h.caption(`${CAPTION[type]}: ${KIND_CAPTION[kind]}`)
  await h.pause(900)
  await h.caption('')
}

/** The state word in the Steckbrief leads to its cause. */
async function followState(h) {
  const state = steckbrief(h).getByTestId('library-state')
  if (await h.visible(state)) await h.click(state, 'Zustand im Steckbrief')
}

const FLOWS = {
  async dokumente(h, kind) {
    await h.type(h.page.getByLabel('Dokumente durchsuchen'), 'Dokument 00', 'Suche')
    await h.pause(900)
    await h.type(h.page.getByLabel('Dokumente durchsuchen'), '', 'Suche leeren')
    await h.click(h.page.getByLabel('Nur Dokumente ohne Wert'), 'Filter ohne Wert')
    await h.click(h.page.getByRole('option', { name: /^ohne Titel$/ }), 'ohne Titel')
    await h.pause(900)
    await h.click(h.page.getByLabel('Nur Dokumente ohne Wert'), 'Filter')
    await h.click(h.page.getByRole('option', { name: /^Alle Dokumente$/ }), 'Alle Dokumente')
    await h.click(button(h, /^Angaben zu .* anzeigen$/).first(), 'Angaben anzeigen')
    if (kind === 'viele') await h.click(h.page.getByRole('button', { name: /page 2$/ }), 'Seite 2')
    await followState(h)
  },

  async quelle(h, kind) {
    await h.click(tab(h, /^Quelle$/), 'Reiter Quelle')
    if (kind === 'fehler') {
      await h.click(button(h, /^Ändern Verbindung$/), 'Verbindung ändern')
      await h.pause(1200)
      await h.click(button(h, /^Abbrechen$/), 'Abbrechen')
    }
    await h.page.evaluate(() => document.getElementById('library-runs-title')?.scrollIntoView({ block: 'start', behavior: 'smooth' }))
    await h.pause(900)
    const first = h.page.locator('#library-tabpanel-quelle .MuiAccordionSummary-root').first()
    if (await h.visible(first)) await h.click(first, 'neuester Lauf')
    const all = button(h, /^alle .* anzeigen$/).first()
    if (await h.visible(all)) {
      await h.click(all, 'alle anzeigen')
      await h.pause(900)
      await h.click(button(h, /^Schließen$/), 'Schließen')
    }
    const technical = button(h, /^Technische Angaben$/).first()
    if (await h.visible(technical)) await h.click(technical, 'Technische Angaben')
  },

  async metadaten(h, kind) {
    await h.click(tab(h, /^Metadaten$/), 'Reiter Metadaten')
    if (kind === 'viele') {
      await h.type(h.page.getByLabel('Werte durchsuchen').first(), 'Wert 01', 'Werte durchsuchen')
      await h.pause(900)
      await h.type(h.page.getByLabel('Werte durchsuchen').first(), '', 'leeren')
      await h.click(button(h, /^Alle \d+ anzeigen$/).first(), 'Alle Werte')
    }
    await h.click(button(h, /^Ändern Kontextpräfix$/), 'Kontextpräfix ändern')
    await h.pause(900)
    await h.click(button(h, /^Schließen$/).first(), 'Schließen')
    await h.click(button(h, /^Ändern Extraktion$/), 'Extraktion ändern')
    await h.pause(1200)
  },

  async freigaben(h, kind) {
    await h.click(tab(h, /^Freigaben$/), 'Reiter Freigaben')
    if (kind === 'viele') {
      await h.type(h.page.getByLabel('Freigaben durchsuchen'), 'Gruppe 01', 'Freigaben durchsuchen')
      await h.pause(1000)
      await h.type(h.page.getByLabel('Freigaben durchsuchen'), '', 'leeren')
    }
    await h.click(button(h, /^Freigeben$/), 'Freigeben')
    await h.pause(1000)
    await h.click(button(h, /^Abbrechen$/), 'Abbrechen')
    await h.click(button(h, /^Anzeigen Herleitung$/), 'Herleitung')
    await h.pause(1200)
  },

  async zuordnungen(h, kind) {
    await h.click(tab(h, /^Zuordnungen$/), 'Reiter Zuordnungen')
    if (kind === 'viele') {
      await h.type(h.page.getByLabel('Spaces durchsuchen'), 'Space 01', 'Spaces durchsuchen')
      await h.pause(1000)
      await h.type(h.page.getByLabel('Spaces durchsuchen'), '', 'leeren')
    }
    await h.click(button(h, /in Space verwenden$/), 'In Space verwenden')
    await h.pause(1200)
    await h.page.keyboard.press('Escape').catch(() => {})
    await h.pause(600)
  },

  async upload(h) {
    await h.click(button(h, /^Hochladen$/).first(), 'Hochladen')
    await h.pause(700)
    await h.page.keyboard.press('Escape').catch(() => {})
    await h.page.locator('input[type=file]').first().setInputFiles(FILES).catch(() => {})
    await h.pause(2500)
    const rejected = button(h, /^Abgelehnte Dateien anzeigen$/).first()
    if (await h.visible(rejected)) {
      await h.click(rejected, 'Abgelehnte Dateien')
      await h.pause(1200)
      await h.click(button(h, /^Schließen$/), 'Schließen')
    }
    await followState(h)
  },

  async abschluss(h) {
    const steps = steckbrief(h).getByTestId('next-steps')
    await h.visible(steps)
    await h.click(steckbrief(h).getByRole('link', { name: /^In Space verwenden$/ }), 'Schritt In Space verwenden')
    await h.pause(900)
    await h.click(link(h, /^Feld anlegen$/).first(), 'Schritt Feld anlegen')
    await h.pause(900)
    await h.click(steckbrief(h).getByRole('button', { name: /^Ausblenden$/ }), 'Ausblenden')
    await h.pause(1200)
  },
}

export async function run(h, { type, kind, scenario }) {
  await open(h, type, scenario, kind, type === 'abschluss' ? '&justCreated=1' : '')
  await FLOWS[type](h, kind)
  await h.pause(1200)
}
