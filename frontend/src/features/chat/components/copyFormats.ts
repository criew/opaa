import type { CitationIndex } from '@/shared/ui/markdown/citations'
import {
  CLAIMED_MARKER_RE,
  readMarker,
  unwrapBracketedMarkers,
} from '@/shared/ui/markdown/citations'

/**
 * The formats a chat answer is copied in. The stored answer carries the backend's citation markers
 * (`【source: <documentId>#<chunk> | <fileName>】`); none of the formats may let them through raw.
 */

/**
 * `content` cut at its markers: `texts[i]` is the text before `markers[i]`, without the blanks
 * directly in front of the marker - the mark attaches to the word it follows - and the last text
 * follows the last marker. Each character is looked at most twice, so the cost is linear.
 */
function splitAtMarkers(content: string): { texts: string[]; markers: string[] } {
  const pattern = new RegExp(CLAIMED_MARKER_RE.source, 'g')
  const texts: string[] = []
  const markers: string[] = []
  let lastEnd = 0
  for (let match = pattern.exec(content); match !== null; match = pattern.exec(content)) {
    let start = match.index
    while (start > lastEnd && (content[start - 1] === ' ' || content[start - 1] === '\t')) start--
    texts.push(content.slice(lastEnd, start))
    markers.push(match[0])
    lastEnd = pattern.lastIndex
  }
  texts.push(content.slice(lastEnd))
  return { texts, markers }
}

/** The answer as Markdown, without footnote markers and without a source list - the default. */
export function answerAsMarkdown(content: string): string {
  return splitAtMarkers(unwrapBracketedMarkers(content)).texts.join('').trim()
}

/**
 * The answer as Markdown with its sources: each marker becomes a Markdown footnote (`[^1]`, the
 * same number the chat shows), repeated neighbours collapse, and the cited documents follow as a
 * footnote list with their Fundort where the pipeline knew one.
 */
export function answerWithSources(answer: string, citations: CitationIndex): string {
  const { texts, markers } = splitAtMarkers(unwrapBracketedMarkers(answer))
  const fileNameByNumber = new Map<number, string>()
  const parts: string[] = []
  // The footnote numbers already written directly before this point, with no text in between -
  // a passage cited twice in a row reads as one footnote, not "[^1][^1]".
  let adjacent = new Set<number>()
  markers.forEach((raw, index) => {
    const between = texts[index]
    if (between.length > 0) adjacent = new Set()
    parts.push(between)
    const marker = readMarker(raw)
    const number = marker && citations.numberByKey.get(marker.key)
    if (marker === undefined || number === undefined || adjacent.has(number)) return
    if (!fileNameByNumber.has(number)) fileNameByNumber.set(number, marker.fileName)
    adjacent.add(number)
    parts.push(`[^${number}]`)
  })
  parts.push(texts[texts.length - 1])
  const body = parts.join('').trim()
  if (fileNameByNumber.size === 0) return body
  const notes = [...fileNameByNumber.entries()]
    .sort(([a], [b]) => a - b)
    .map(([number, fileName]) => {
      const location = citations.locationByNumber.get(number)
      return `[^${number}]: ${fileName}${location ? `, ${location}` : ''}`
    })
  return `${body}\n\n${notes.join('\n')}`
}

// Each rule looks at most 300 characters ahead per position, so a line built to make them
// backtrack still costs time linear in its length.
const INLINE_RULES: [RegExp, string][] = [
  [/!\[([^\]]{0,300})\]\([^)]{0,300}\)/g, '$1'], // image -> its alt text
  [/\[([^\]]{1,300})\]\([^)]{0,300}\)/g, '$1'], // link -> its text
  [/`([^`]+)`/g, '$1'],
  [/(\*\*|__)(.{1,300}?)\1/g, '$2'],
  [/~~(.{1,300}?)~~/g, '$1'],
  [/(^|[^\w*])\*(?!\s)(.{1,300}?)(?<!\s)\*(?!\w)/g, '$1$2'],
  [/(^|[^\w_])_(?!\s)(.{1,300}?)(?<!\s)_(?!\w)/g, '$1$2'],
]

function plainInline(line: string): string {
  return INLINE_RULES.reduce(
    (text, [pattern, replacement]) => text.replace(pattern, replacement),
    line,
  )
}

/**
 * The answer as plain text: no Markdown syntax left, but still readable - headings and quotes
 * lose their marks, bullets become "•", table rows become tab-separated cells (they paste into a
 * spreadsheet), code keeps its lines.
 */
export function answerAsPlainText(content: string): string {
  const lines: string[] = []
  let inCode = false
  for (const line of answerAsMarkdown(content).split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) {
      inCode = !inCode
      continue
    }
    if (inCode) {
      lines.push(line)
      continue
    }
    if (/^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/.test(line) && line.includes('-')) {
      if (line.includes('|')) continue // table separator row
      lines.push('') // thematic break
      continue
    }
    if (/^\s*\|.*\|\s*$/.test(line)) {
      const cells = line.trim().slice(1, -1).split('|')
      lines.push(cells.map((cell) => plainInline(cell.trim())).join('\t'))
      continue
    }
    const text = line
      .replace(/^\s{0,3}#{1,6}\s+/, '')
      .replace(/^\s*>\s?/, '')
      .replace(/^(\s*)[-*+]\s+(\[[ xX]\]\s+)?/, '$1• ')
    lines.push(plainInline(text))
  }
  return lines
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/**
 * The note appended when an answer is copied with the copy button: the authority's notice from
 * the branding, the product name and the date of the answer (German format). Deliberately without
 * a model name - that would reveal the installation's configuration. It is visible text, not a
 * machine-readable marking.
 */
export function copyNote(notice: string, productName: string, answeredAt: Date): string {
  const date = answeredAt.toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
  return `${plainNoteText(notice)} – ${plainNoteText(productName)}, ${date}`
}

/**
 * Notice and product name come from the branding. Direction controls (U+202A–202E, U+2066–2069,
 * U+200E/F, U+061C) would reorder the rest of the line when the note is pasted, and breaks or
 * control characters would split it, so they are dropped or turned into blanks.
 */
function plainNoteText(text: string): string {
  return Array.from(text.replace(/[\r\n\u2028\u2029]+/g, ' '))
    .filter((char) => !isDroppedFromNote(char.codePointAt(0) ?? 0))
    .join('')
    .trim()
}

function isDroppedFromNote(code: number): boolean {
  return (
    code <= 0x1f ||
    (code >= 0x7f && code <= 0x9f) ||
    code === 0x61c ||
    code === 0x200e ||
    code === 0x200f ||
    (code >= 0x202a && code <= 0x202e) ||
    (code >= 0x2066 && code <= 0x2069)
  )
}

/** Markdown followed by a rule and the note as its own paragraph. */
export function markdownWithNote(markdown: string, note: string): string {
  return `${markdown}\n\n---\n\n${note}`
}

/** Plain text followed by the note as its own paragraph. */
export function plainTextWithNote(text: string, note: string): string {
  return `${text}\n\n${note}`
}
