import { formatDateTime } from '@/shared/utils/formatDate'

/** What the original view of a mail shows: the head and the plain text as paragraphs. */
export interface MailMessage {
  subject?: string
  from?: string
  to?: string
  cc?: string
  /** The date as „05.03.2026, 09:00“, or as written where it cannot be read. */
  date?: string
  paragraphs: string[]
}

/** Nesting of multipart bodies followed at most; deeper parts count as missing. */
const MAX_DEPTH = 8

interface Part {
  headers: Map<string, string>
  /** The body as a binary string: one character per byte. */
  body: string
}

/**
 * Reads an RFC 5322 message: the head (with RFC 2047 encoded words) and the first text/plain part,
 * following multipart bodies and decoding quoted-printable and base64 in the part's charset.
 * `null` when the message has no plain text part (an HTML-only mail); its Textfassung then stands
 * in. Nothing of the message is rendered as HTML.
 */
export function parseMail(raw: Uint8Array | string): MailMessage | null {
  const bytes = typeof raw === 'string' ? new TextEncoder().encode(raw) : raw
  const message = splitPart(binaryString(bytes))
  const text = plainText(message, 0)
  if (text === null) return null
  const header = (name: string) => {
    const value = message.headers.get(name)
    return value ? decodeHeader(value) : undefined
  }
  const date = header('date')
  const parsed = date ? new Date(date) : null
  return {
    subject: header('subject'),
    from: header('from'),
    to: header('to'),
    cc: header('cc'),
    date: parsed && !Number.isNaN(parsed.getTime()) ? formatDateTime(parsed) : date,
    paragraphs: text
      .replace(/\r\n?/g, '\n')
      .split(/\n[ \t]*\n/)
      .map(trimParagraph)
      .filter((paragraph) => paragraph.length > 0),
  }
}

/**
 * Drops trailing blanks of every line and empty lines at both ends, line by line: a regular
 * expression anchored at line ends backtracks quadratically over a long run of blanks.
 */
function trimParagraph(paragraph: string): string {
  const lines = paragraph.split('\n').map((line) => line.trimEnd())
  let start = 0
  let end = lines.length
  while (start < end && lines[start] === '') start++
  while (end > start && lines[end - 1] === '') end--
  return lines.slice(start, end).join('\n')
}

function binaryString(bytes: Uint8Array): string {
  let out = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    out += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return out
}

function bytesOf(binary: string): Uint8Array {
  return Uint8Array.from(binary, (char) => char.charCodeAt(0) & 0xff)
}

function splitPart(binary: string): Part {
  const normalized = binary.replace(/\r\n/g, '\n')
  const end = normalized.startsWith('\n') ? 0 : normalized.indexOf('\n\n')
  const head = end < 0 ? normalized : normalized.slice(0, end)
  const body = end < 0 ? '' : normalized.slice(end === 0 ? 1 : end + 2)
  const headers = new Map<string, string>()
  for (const line of head.replace(/\n[ \t]+/g, ' ').split('\n')) {
    const colon = line.indexOf(':')
    if (colon <= 0) continue
    const name = line.slice(0, colon).trim().toLowerCase()
    // the first occurrence counts, as in every mail client
    if (!headers.has(name)) headers.set(name, line.slice(colon + 1).trim())
  }
  return { headers, body }
}

function parameter(value: string, name: string): string | undefined {
  const match = new RegExp(`;\\s*${name}\\s*=\\s*(?:"([^"]*)"|([^;\\s]+))`, 'i').exec(value)
  return match ? (match[1] ?? match[2]) : undefined
}

function plainText(part: Part, depth: number): string | null {
  if (depth > MAX_DEPTH) return null
  const type = part.headers.get('content-type') ?? 'text/plain'
  const essence = type.split(';')[0].trim().toLowerCase()
  if (essence.startsWith('multipart/')) {
    const boundary = parameter(type, 'boundary')
    if (!boundary) return null
    for (const child of multipartChildren(part.body, boundary)) {
      const disposition = (child.headers.get('content-disposition') ?? '').toLowerCase()
      if (disposition.startsWith('attachment')) continue
      const text = plainText(child, depth + 1)
      if (text !== null) return text
    }
    return null
  }
  if (essence !== 'text/plain') return null
  const encoding = (part.headers.get('content-transfer-encoding') ?? '').trim().toLowerCase()
  const charset = parameter(type, 'charset') ?? 'utf-8'
  const bytes =
    encoding === 'base64'
      ? base64Bytes(part.body)
      : encoding === 'quoted-printable'
        ? quotedPrintableBytes(part.body)
        : bytesOf(part.body)
  return decode(bytes, charset)
}

function multipartChildren(body: string, boundary: string): Part[] {
  const delimiter = `--${boundary}`
  const parts: Part[] = []
  let current: string[] | null = null
  for (const line of body.split('\n')) {
    const trimmed = line.trimEnd()
    if (trimmed === `${delimiter}--`) break
    if (trimmed === delimiter) {
      if (current) parts.push(splitPart(current.join('\n')))
      current = []
      continue
    }
    current?.push(line)
  }
  if (current) parts.push(splitPart(current.join('\n')))
  return parts
}

function base64Bytes(text: string): Uint8Array {
  try {
    return bytesOf(atob(text.replace(/[^A-Za-z0-9+/=]/g, '')))
  } catch {
    return new Uint8Array()
  }
}

function quotedPrintableBytes(text: string, underscoreIsSpace = false): Uint8Array {
  const source = text.replace(/=\r?\n/g, '')
  const bytes: number[] = []
  for (let i = 0; i < source.length; i++) {
    const char = source[i]
    const hex = source.slice(i + 1, i + 3)
    if (char === '=' && /^[0-9A-Fa-f]{2}$/.test(hex)) {
      bytes.push(parseInt(hex, 16))
      i += 2
    } else if (char === '_' && underscoreIsSpace) {
      bytes.push(0x20)
    } else {
      bytes.push(char.charCodeAt(0) & 0xff)
    }
  }
  return Uint8Array.from(bytes)
}

function decode(bytes: Uint8Array, charset: string): string {
  try {
    return new TextDecoder(charset.trim()).decode(bytes)
  } catch {
    return new TextDecoder().decode(bytes)
  }
}

/** A header value: raw 8-bit bytes read as UTF-8, then RFC 2047 encoded words decoded. */
function decodeHeader(binary: string): string {
  return decode(bytesOf(binary), 'utf-8')
    .replace(/(=\?[^?\s]+\?[BbQq]\?[^?\s]*\?=)\s+(?==\?)/g, '$1')
    .replace(/=\?([^?\s]+)\?([BbQq])\?([^?\s]*)\?=/g, (_, charset: string, kind: string, data) =>
      decode(
        kind.toUpperCase() === 'B' ? base64Bytes(data) : quotedPrintableBytes(data, true),
        charset.split('*')[0],
      ),
    )
}
