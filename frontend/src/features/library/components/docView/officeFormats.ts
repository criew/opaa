import type { ChunkAnchor } from '@/shared/api/types'
import { formatDateTime } from '@/shared/utils/formatDate'

export type OfficeFormat = 'docx' | 'csv' | 'mail'

/** An office original the lab shows as itself rather than as its Textfassung. */
export interface OfficeOriginal {
  format: OfficeFormat
  fileName: string
  blob: Blob
  /** The text of a CSV or mail; a Word file is rendered from {@link blob}. */
  text?: string
  anchor?: ChunkAnchor | null
}

const FORMAT_BY_EXTENSION: Record<string, OfficeFormat> = { docx: 'docx', csv: 'csv', eml: 'mail' }

/** The lab's own original view for `fileName`, or `null` when it keeps the Textfassung. */
export function officeFormat(fileName: string): OfficeFormat | null {
  const dot = fileName.lastIndexOf('.')
  return dot < 0 ? null : (FORMAT_BY_EXTENSION[fileName.slice(dot + 1).toLowerCase()] ?? null)
}

/** Parses CSV with `;` or `,` as separator (whichever the head line uses more), quotes per RFC 4180. */
export function parseCsv(text: string): string[][] {
  const firstLine = text.slice(0, text.search(/\r?\n|$/))
  const separator =
    (firstLine.match(/;/g)?.length ?? 0) >= (firstLine.match(/,/g)?.length ?? 0) ? ';' : ','
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') {
        cell += '"'
        i++
      } else if (char === '"') quoted = false
      else cell += char
    } else if (char === '"') quoted = true
    else if (char === separator) {
      row.push(cell)
      cell = ''
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else cell += char
  }
  if (cell || row.length) rows.push([...row, cell])
  return rows
}

export interface ParsedMail {
  subject?: string
  from?: string
  to?: string
  cc?: string
  date?: string
  body: string
}

/** Header block and the first text/plain part of an RFC 5322 message, as plain text. */
export function parseMail(raw: string): ParsedMail {
  const { headers, body } = splitMessage(raw)
  const text = plainText(headers, body)
  const date = headers.get('date')
  const parsed = date ? new Date(date) : null
  return {
    subject: headers.get('subject'),
    from: headers.get('from'),
    to: headers.get('to'),
    cc: headers.get('cc'),
    date: parsed && !Number.isNaN(parsed.getTime()) ? formatDateTime(parsed.toISOString()) : date,
    body: text,
  }
}

function splitMessage(raw: string) {
  const normalized = raw.replace(/\r\n/g, '\n')
  const end = normalized.indexOf('\n\n')
  const head = end < 0 ? normalized : normalized.slice(0, end)
  const body = end < 0 ? '' : normalized.slice(end + 2)
  const headers = new Map<string, string>()
  for (const line of head.replace(/\n[ \t]+/g, ' ').split('\n')) {
    const colon = line.indexOf(':')
    if (colon > 0)
      headers.set(
        line.slice(0, colon).trim().toLowerCase(),
        decodeWords(line.slice(colon + 1).trim()),
      )
  }
  return { headers, body }
}

function plainText(headers: Map<string, string>, body: string): string {
  const type = headers.get('content-type') ?? 'text/plain'
  const boundary = /boundary="?([^";]+)"?/i.exec(type)?.[1]
  if (/^multipart\//i.test(type) && boundary) {
    for (const part of body.split(`--${boundary}`).slice(1)) {
      if (part.startsWith('--')) break
      const inner = splitMessage(part.replace(/^\n/, ''))
      const innerType = inner.headers.get('content-type') ?? 'text/plain'
      if (/^(text\/plain|multipart\/)/i.test(innerType)) return plainText(inner.headers, inner.body)
    }
    return ''
  }
  const encoding = (headers.get('content-transfer-encoding') ?? '').toLowerCase()
  const charset = /charset="?([^";]+)"?/i.exec(type)?.[1] ?? 'utf-8'
  if (encoding === 'base64') return decodeBytes(base64Bytes(body), charset)
  if (encoding === 'quoted-printable') return decodeBytes(quotedPrintableBytes(body), charset)
  return body
}

function base64Bytes(text: string): Uint8Array {
  const binary = atob(text.replace(/\s/g, ''))
  return Uint8Array.from(binary, (c) => c.charCodeAt(0))
}

function quotedPrintableBytes(text: string, underscoreIsSpace = false): Uint8Array {
  const source = text.replace(/=\n/g, '')
  const bytes: number[] = []
  for (let i = 0; i < source.length; i++) {
    const char = source[i]
    if (char === '=' && /^[0-9A-F]{2}$/i.test(source.slice(i + 1, i + 3))) {
      bytes.push(parseInt(source.slice(i + 1, i + 3), 16))
      i += 2
    } else if (char === '_' && underscoreIsSpace) bytes.push(0x20)
    else bytes.push(...new TextEncoder().encode(char))
  }
  return Uint8Array.from(bytes)
}

function decodeBytes(bytes: Uint8Array, charset: string): string {
  try {
    return new TextDecoder(charset).decode(bytes)
  } catch {
    return new TextDecoder().decode(bytes)
  }
}

/** RFC 2047 encoded words in a header (`=?utf-8?B?…?=`, `=?iso-8859-1?Q?…?=`). */
function decodeWords(value: string): string {
  return value
    .replace(/\?=\s+=\?/g, '?==?')
    .replace(/=\?([^?]+)\?([BQ])\?([^?]*)\?=/gi, (_, charset: string, kind: string, data: string) =>
      decodeBytes(
        kind.toUpperCase() === 'B' ? base64Bytes(data) : quotedPrintableBytes(data, true),
        charset,
      ),
    )
}
