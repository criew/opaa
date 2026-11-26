import type { ChunkAnchor } from '@/shared/api/types'
import { getDocumentContent } from '../api/libraryApi'
import { parseCsv, type CsvTable } from './csvTable'
import { TEXT_PREVIEW_MAX_BYTES } from './documentContent'
import { parseMail, type MailMessage } from './mailMessage'

/** The office formats „Dokument öffnen“ shows as themselves rather than as their Textfassung. */
export type OriginalFormat = 'docx' | 'csv' | 'mail'

const FORMAT_BY_EXTENSION: Record<string, OriginalFormat> = {
  docx: 'docx',
  csv: 'csv',
  eml: 'mail',
}

/**
 * Largest original shown as itself, per format; a larger one opens as its Textfassung. A table is
 * rendered row by row, so it gets the text preview's bound; a Word file or a mail carries images
 * and attachments that are not rendered or only decoded on demand.
 */
export const ORIGINAL_VIEW_MAX_BYTES: Record<OriginalFormat, number> = {
  docx: 10 * 1024 * 1024,
  csv: TEXT_PREVIEW_MAX_BYTES,
  mail: 10 * 1024 * 1024,
}

interface OriginalBase {
  documentId: string
  fileName: string
  /** The original as fetched, so the view's download hands out its exact bytes. */
  blob: Blob
  /** As in the other views: `null` is a citation without a stored anchor. */
  anchor?: ChunkAnchor | null
  /** The cited passage, for the Textfassung the view falls back to. */
  chunkIndex?: number | null
}

export type OfficeOriginal =
  | (OriginalBase & { format: 'docx' })
  | (OriginalBase & { format: 'csv'; table: CsvTable })
  | (OriginalBase & { format: 'mail'; mail: MailMessage })

/** The original view for `fileName`, or `null` where it keeps the Textfassung. */
export function originalFormat(fileName: string): OriginalFormat | null {
  const dot = fileName.lastIndexOf('.')
  return dot < 0 ? null : (FORMAT_BY_EXTENSION[fileName.slice(dot + 1).toLowerCase()] ?? null)
}

/**
 * Fetches the original through the content endpoint and reads it for its view. `null` where the
 * Textfassung stands in instead: a file above {@link ORIGINAL_VIEW_MAX_BYTES}, a mail without a
 * plain text part. A failed fetch rejects.
 */
export async function loadOfficeOriginal(
  format: OriginalFormat,
  document: Omit<OriginalBase, 'blob'>,
): Promise<OfficeOriginal | null> {
  const { blob, fileName } = await getDocumentContent(document.documentId)
  if (blob.size > ORIGINAL_VIEW_MAX_BYTES[format]) return null
  const base = { ...document, fileName: fileName ?? document.fileName, blob }
  if (format === 'docx') return { ...base, format }
  if (format === 'csv') return { ...base, format, table: parseCsv(await blob.text()) }
  const mail = parseMail(new Uint8Array(await blob.arrayBuffer()))
  return mail ? { ...base, format, mail } : null
}
