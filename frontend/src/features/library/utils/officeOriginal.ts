import type { ChunkAnchor } from '@/shared/api/types'
import { getDocumentContent } from '../api/libraryApi'
import { decodeCsv, parseCsv, type CsvTable } from './csvTable'
import { TEXT_PREVIEW_MAX_BYTES } from './documentContent'
import { parseMail, type MailMessage } from './mailMessage'
import { declaredUnpackedSize } from './zipSize'

/** The office formats „Dokument öffnen“ shows as themselves rather than as their Textfassung. */
export type OriginalFormat = 'docx' | 'csv' | 'mail'

const FORMAT_BY_EXTENSION: Record<string, OriginalFormat> = {
  docx: 'docx',
  csv: 'csv',
  eml: 'mail',
}

/**
 * Largest original shown as itself, per format; a larger one opens as its Textfassung. A table is
 * rendered row by row, so it gets the text preview's bound. A Word file's images are rendered as
 * data URLs, so their bytes count twice in memory; a mail's attachments are skipped unread.
 */
export const ORIGINAL_VIEW_MAX_BYTES: Record<OriginalFormat, number> = {
  docx: 10 * 1024 * 1024,
  csv: TEXT_PREVIEW_MAX_BYTES,
  mail: 10 * 1024 * 1024,
}

/** Largest sum of unpacked sizes a Word file may declare; more is likely a ZIP bomb. */
export const DOCX_UNPACKED_MAX_BYTES = 50 * 1024 * 1024

/** Why an original opens as its Textfassung instead. */
export type OriginalFallback = 'too-large' | 'no-plain-text' | 'failed'

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
 * Fetches the original through the content endpoint and reads it for its view, or names why the
 * Textfassung stands in instead: a file above {@link ORIGINAL_VIEW_MAX_BYTES}, a Word file that
 * declares more than {@link DOCX_UNPACKED_MAX_BYTES} unpacked, a mail without a plain text part.
 * A failed fetch rejects.
 */
export async function loadOfficeOriginal(
  format: OriginalFormat,
  document: Omit<OriginalBase, 'blob'>,
): Promise<OfficeOriginal | { fallback: OriginalFallback }> {
  const { blob, fileName } = await getDocumentContent(document.documentId)
  if (blob.size > ORIGINAL_VIEW_MAX_BYTES[format]) return { fallback: 'too-large' }
  const base = { ...document, fileName: fileName ?? document.fileName, blob }
  const bytes = new Uint8Array(await blob.arrayBuffer())
  if (format === 'docx') {
    const unpacked = declaredUnpackedSize(bytes)
    return unpacked != null && unpacked > DOCX_UNPACKED_MAX_BYTES
      ? { fallback: 'too-large' }
      : { ...base, format }
  }
  if (format === 'csv') return { ...base, format, table: parseCsv(decodeCsv(bytes)) }
  const mail = parseMail(bytes)
  return mail ? { ...base, format, mail } : { fallback: 'no-plain-text' }
}
