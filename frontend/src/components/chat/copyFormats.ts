import type { CitationIndex } from './citations'
import { CLAIMED_MARKER_RE, readMarker } from './citations'

/**
 * The formats a chat answer is copied in. The stored answer carries the backend's citation markers
 * (`【source: <documentId>#<chunk> | <fileName>】`); none of the formats may let them through raw.
 */

/** A marker together with the whitespace before it - the mark attaches to the word it follows. */
function markerWithLeadingSpace(): RegExp {
  return new RegExp(`[ \\t]*${CLAIMED_MARKER_RE.source}`, 'g')
}

/** The answer as Markdown, without footnote markers and without a source list - the default. */
export function answerAsMarkdown(content: string): string {
  return content.replace(markerWithLeadingSpace(), '').trim()
}

/**
 * The answer as Markdown with its sources: each marker becomes a Markdown footnote (`[^1]`, the
 * same number the chat shows), repeated neighbours collapse, and the cited documents follow as a
 * footnote list with their Fundort where the pipeline knew one.
 */
export function answerWithSources(content: string, citations: CitationIndex): string {
  const fileNameByNumber = new Map<number, string>()
  const pattern = markerWithLeadingSpace()
  let body = ''
  let lastEnd = 0
  // The footnote numbers already written directly before this point, with no text in between -
  // a passage cited twice in a row reads as one footnote, not "[^1][^1]".
  let adjacent = new Set<number>()
  for (let match = pattern.exec(content); match !== null; match = pattern.exec(content)) {
    const between = content.slice(lastEnd, match.index)
    if (between.length > 0) adjacent = new Set()
    body += between
    lastEnd = pattern.lastIndex
    const marker = readMarker(match[0])
    const number = marker && citations.numberByKey.get(marker.key)
    if (marker === undefined || number === undefined || adjacent.has(number)) continue
    if (!fileNameByNumber.has(number)) fileNameByNumber.set(number, marker.fileName.trim())
    adjacent.add(number)
    body += `[^${number}]`
  }
  body = (body + content.slice(lastEnd)).trim()
  if (fileNameByNumber.size === 0) return body
  const notes = [...fileNameByNumber.entries()]
    .sort(([a], [b]) => a - b)
    .map(([number, fileName]) => {
      const location = citations.locationByNumber.get(number)
      return `[^${number}]: ${fileName}${location ? `, ${location}` : ''}`
    })
  return `${body}\n\n${notes.join('\n')}`
}

const INLINE_RULES: [RegExp, string][] = [
  [/!\[([^\]]*)\]\([^)]*\)/g, '$1'], // image -> its alt text
  [/\[([^\]]+)\]\([^)]*\)/g, '$1'], // link -> its text
  [/`([^`]+)`/g, '$1'],
  [/(\*\*|__)(.+?)\1/g, '$2'],
  [/~~(.+?)~~/g, '$1'],
  [/(^|[^\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)/g, '$1$2'],
  [/(^|[^\w_])_(?!\s)(.+?)(?<!\s)_(?!\w)/g, '$1$2'],
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
