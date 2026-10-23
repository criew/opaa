import { readFile } from 'node:fs/promises'
import { inflateRawSync } from 'node:zlib'
import { expect, type Page } from '@playwright/test'

/**
 * Bausteine der Szenarien zur Selbstauskunft (test(e2e) #2994, Epic #2986): die Seite „Meine
 * Daten“, der Download über das versteckte Formular und das Lesen des ZIP. Geteilt zwischen dem
 * Ziel `e2e` (tests/self-disclosure.spec.ts) und dem Ziel `local-auth`
 * (local-auth/tests/09-selbstauskunft.spec.ts), das als einziges eine echte Passwortbestätigung
 * kennt.
 */

export const MY_DATA_PATH = '/settings/data'

/** Der Hinweis gegen erzwungene Vorlage, wörtlich wie in der Spezifikation. */
export const PERSONAL_ONLY_NOTICE =
  'Diese Auskunft ist nur für Sie. Niemand darf verlangen, dass Sie sie vorlegen oder weitergeben.'

export const COMPLETED_MESSAGE = 'Ihre Daten wurden vollständig heruntergeladen.'

export const DOWNLOAD_BUTTON = 'Daten herunterladen'

/**
 * Waits until „Meine Daten“ has loaded the status: the daily limit above the button, or the
 * notice that replaces it. Until then the button swallows a click.
 */
export async function waitForMyDataStatus(page: Page): Promise<void> {
  await expect(page.getByText(PERSONAL_ONLY_NOTICE)).toBeVisible({ timeout: 20_000 })
  await expect(
    page.getByText(/pro Tag möglich\.|in dieser Installation derzeit abgeschaltet/).first(),
  ).toBeVisible({ timeout: 20_000 })
}

/** Opens „Meine Daten“ and waits for its status. */
export async function openMyData(page: Page): Promise<void> {
  await page.goto(MY_DATA_PATH)
  await waitForMyDataStatus(page)
}

/** The entries of a ZIP archive by name, uncompressed. */
export type ZipEntries = Map<string, Buffer>

/**
 * Reads a ZIP from its central directory - no dependency for one archive format. Handles the two
 * methods java.util.zip writes (stored, deflated); ZIP64 is out of scope for an export of this
 * size and fails loudly.
 */
export function readZip(archive: Buffer): ZipEntries {
  const END_OF_CENTRAL_DIRECTORY = 0x06054b50
  let end = -1
  for (let i = archive.length - 22; i >= Math.max(0, archive.length - 22 - 0xffff); i--) {
    if (archive.readUInt32LE(i) === END_OF_CENTRAL_DIRECTORY) {
      end = i
      break
    }
  }
  if (end < 0) throw new Error('Kein ZIP: Ende des Zentralverzeichnisses fehlt')
  const count = archive.readUInt16LE(end + 10)
  let offset = archive.readUInt32LE(end + 16)
  const entries: ZipEntries = new Map()
  for (let n = 0; n < count; n++) {
    if (archive.readUInt32LE(offset) !== 0x02014b50) {
      throw new Error(`Zentralverzeichnis beschädigt bei Byte ${offset}`)
    }
    const method = archive.readUInt16LE(offset + 10)
    const compressedSize = archive.readUInt32LE(offset + 20)
    const nameLength = archive.readUInt16LE(offset + 28)
    const extraLength = archive.readUInt16LE(offset + 30)
    const commentLength = archive.readUInt16LE(offset + 32)
    const localHeader = archive.readUInt32LE(offset + 42)
    const name = archive.toString('utf8', offset + 46, offset + 46 + nameLength)
    if (compressedSize === 0xffffffff || localHeader === 0xffffffff) {
      throw new Error(`ZIP64 wird nicht gelesen (${name})`)
    }
    const dataStart =
      localHeader +
      30 +
      archive.readUInt16LE(localHeader + 26) +
      archive.readUInt16LE(localHeader + 28)
    const data = archive.subarray(dataStart, dataStart + compressedSize)
    if (method === 0) entries.set(name, Buffer.from(data))
    else if (method === 8) entries.set(name, inflateRawSync(data))
    else throw new Error(`Unbekannte Kompressionsmethode ${method} (${name})`)
    offset += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

/** Every entry as text, for "appears nowhere in the export" checks. */
export function allText(entries: ZipEntries): string {
  return [...entries.values()].map((content) => content.toString('utf8')).join('\n')
}

/** One JSON entry of the export, parsed. */
export function jsonEntry<T = unknown>(entries: ZipEntries, name: string): T {
  const content = entries.get(name)
  expect(
    content,
    `${name} fehlt im ZIP (vorhanden: ${[...entries.keys()].join(', ')})`,
  ).toBeDefined()
  return JSON.parse(content!.toString('utf8')) as T
}

export interface SelfDisclosureDownload {
  fileName: string
  entries: ZipEntries
}

/**
 * Starts a download with `start` (the click on the button, or the confirmation in the dialog) and
 * returns the ZIP the browser saved. The ZIP arrives through a native form submission into a hidden
 * frame; Playwright reports it as a download of the page.
 */
export async function captureDownload(
  page: Page,
  start: () => Promise<void>,
): Promise<SelfDisclosureDownload> {
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    start(),
  ])
  const failure = await download.failure()
  expect(failure, 'Download des ZIP schlug im Browser fehl').toBeNull()
  const path = await download.path()
  return { fileName: download.suggestedFilename(), entries: readZip(await readFile(path)) }
}

/** The manifest's categories as the export lists them. */
export interface ManifestCategory {
  id: string
  title: string
  file: string | null
  records: number
}

/**
 * Asserts the structure every export has: `index.html` and `manifest.json`, and the categories
 * account and chats with records in their own file.
 */
export function expectExportStructure(download: SelfDisclosureDownload): ManifestCategory[] {
  expect(download.fileName).toMatch(/^opaa-selbstauskunft-\d{4}-\d{2}-\d{2}\.zip$/)
  expect(download.entries.has('index.html'), 'index.html fehlt im ZIP').toBe(true)
  const manifest = jsonEntry<{ categories: ManifestCategory[] }>(download.entries, 'manifest.json')
  for (const id of ['accounts', 'chats']) {
    const category = manifest.categories.find((candidate) => candidate.id === id)
    expect(category, `Kategorie ${id} fehlt im Manifest`).toBeDefined()
    expect(category!.records, `Kategorie ${id} ohne Einträge`).toBeGreaterThan(0)
    expect(download.entries.has(category!.file ?? ''), `${category!.file} fehlt im ZIP`).toBe(true)
  }
  return manifest.categories
}

/** One message of the category chats, as far as the scenarios read it. */
export interface ExportedMessage {
  chat: string
  role: string
  content: string
  sources: string[]
}

/** The records of one section of a category file (`{ sections: [{ key, records }] }`). */
export function sectionRecords<T = Record<string, unknown>>(
  entries: ZipEntries,
  file: string,
  section: string,
): T[] {
  const category = jsonEntry<{ sections: Array<{ key: string; records: T[] }> }>(entries, file)
  return category.sections.find((candidate) => candidate.key === section)?.records ?? []
}

/** The section messages of `chats.json`. */
export function exportedMessages(entries: ZipEntries): ExportedMessage[] {
  return sectionRecords<ExportedMessage>(entries, 'chats.json', 'messages')
}
