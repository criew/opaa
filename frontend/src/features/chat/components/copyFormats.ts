import type { AgentRun } from '@/shared/api/types'
import type { CitationIndex } from '@/shared/ui/markdown/citations'
import {
  CLAIMED_MARKER_RE,
  readMarker,
  unwrapBracketedMarkers,
} from '@/shared/ui/markdown/citations'
import type { OutwardPolicy } from '@/shared/ui/markdown/outwardMarkdown'
import { answerForOutside } from '@/shared/ui/markdown/outwardMarkdown'
import { plainNoteText } from './copyNote'

/**
 * The formats a chat answer is copied in. The stored answer carries the backend's citation markers
 * (`【source: <documentId>#<chunk> | <fileName>】`); none of the formats may let them through raw.
 * Every format ends in {@link answerForOutside}, so no image, foreign link or raw HTML of the
 * answer reaches the program the text is pasted into.
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
export function answerAsMarkdown(content: string, policy: OutwardPolicy = {}): string {
  const markdown = splitAtMarkers(unwrapBracketedMarkers(content)).texts.join('').trim()
  return answerForOutside(markdown, policy).trim()
}

/**
 * The answer as Markdown with its sources: each marker becomes a Markdown footnote (`[^1]`, the
 * same number the chat shows), repeated neighbours collapse, and the cited documents follow as a
 * footnote list with their Fundort where the pipeline knew one. `labelPrefix` goes in front of
 * every footnote label (`[^2-1]`), for documents that hold several answers.
 */
export function answerWithSources(
  answer: string,
  citations: CitationIndex,
  options: OutwardPolicy & { labelPrefix?: string } = {},
): string {
  const labelPrefix = options.labelPrefix ?? ''
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
    parts.push(`[^${labelPrefix}${number}]`)
  })
  parts.push(texts[texts.length - 1])
  const body = parts.join('').trim()
  if (fileNameByNumber.size === 0) return answerForOutside(body, options).trim()
  const notes = [...fileNameByNumber.entries()]
    .sort(([a], [b]) => a - b)
    .map(([number, fileName]) => {
      const location = citations.locationByNumber.get(number)
      return `[^${labelPrefix}${number}]: ${fileName}${location ? `, ${location}` : ''}`
    })
  return answerForOutside(`${body}\n\n${notes.join('\n')}`, options).trim()
}

// Each rule looks at most 300 characters ahead per position, so a line built to make them
// backtrack still costs time linear in its length. A bracket written as an escape is text, not
// syntax; escapes are resolved after the syntax rules, so the text keeps no backslash. What the
// resolved escapes form anew is not syntax either: an image becomes its alt text, a link its text
// with the address in parentheses, so no live link reaches a Markdown reader.
const INLINE_RULES: [RegExp, string][] = [
  [/(?<!\\)!\[([^\]]{0,300})(?<!\\)\]\([^)]{0,300}\)/g, '$1'], // image -> its alt text
  [/(?<!\\)\[([^\]]{1,300})(?<!\\)\]\([^)]{0,300}\)/g, '$1'], // link -> its text
  [/(\*\*|__)(.{1,300}?)\1/g, '$2'],
  [/~~(.{1,300}?)~~/g, '$1'],
  [/(^|[^\w*])\*(?!\s)(.{1,300}?)(?<!\s)\*(?!\w)/g, '$1$2'],
  [/(^|[^\w_])_(?!\s)(.{1,300}?)(?<!\s)_(?!\w)/g, '$1$2'],
  [/\\([!-/:-@[-`{-~])/g, '$1'],
  [/!\[([^\]]{0,300})\]\([^)]{0,300}\)/g, '$1'],
  [/\[([^\]]{1,300})\]\(([^)]{0,300})\)/g, '$1 ($2)'],
  // Any tag stays text when the plain text is pasted into a Markdown or HTML reader.
  [/<(?=[A-Za-z/!?])/g, '&lt;'],
]

/**
 * A code span of up to 1000 characters. Its content is kept as written, past every rule; it keeps
 * its backticks when it holds `<`, a bracket or a parenthesis, so a tag, link or image stays code in a
 * Markdown reader.
 */
const CODE_SPAN = /(`+)(?!`)([^`]|[^`][\s\S]{0,1000}?[^`])\1(?!`)/g

/** Stands in for the code spans of a line while the rules run; never part of an answer. */
const SLOT = /\uE000(\d+)\uE001/g

function plainInline(line: string): string {
  const spans: string[] = []
  const withSlots = line.replace(/[\uE000\uE001]/g, '').replace(CODE_SPAN, (span, _, code) => {
    const padded = /^ [\s\S]* $/.test(code) && code.trim() !== ''
    spans.push(/[<[\]()]/.test(code) ? span : padded ? code.slice(1, -1) : code)
    return `\uE000${spans.length - 1}\uE001`
  })
  const plain = INLINE_RULES.reduce(
    (text, [pattern, replacement]) => text.replace(pattern, replacement),
    withSlots,
  )
  return plain.replace(SLOT, (_, index: string) => spans[Number(index)])
}

/**
 * The answer as plain text: no Markdown syntax left, but still readable - headings and quotes
 * lose their marks, bullets become "•", table rows become tab-separated cells (they paste into a
 * spreadsheet), code keeps its lines.
 */
export function answerAsPlainText(content: string, policy: OutwardPolicy = {}): string {
  const lines: string[] = []
  let inCode = false
  for (const line of answerAsMarkdown(content, policy).split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) {
      inCode = !inCode
      continue
    }
    if (inCode) {
      lines.push(line)
      continue
    }
    // Tested on the trimmed line: blanks at either end would make the pattern ambiguous, and a
    // long run of them quadratic.
    if (line.includes('-') && /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?$/.test(line.trim())) {
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

export { copyNote } from './copyNote'

/**
 * The draft notice of an agent answer with agent and revision, so the mark travels with a copied
 * or exported answer; `null` for an answer without an agent run.
 */
export function agentDraftNote(run: AgentRun | undefined): string | null {
  if (!run) return null
  const notice = 'Entwurf, nicht geprüft'
  if (!run.stamp) return notice
  const agent = run.agentName ? `Agent „${plainNoteText(run.agentName)}“` : 'Agent'
  return `${notice} · ${agent}, Fassung ${run.stamp.agentRevision}`
}

/** The copy note led by the draft notice of an agent answer, the note alone otherwise. */
export function noteWithDraft(note: string, run: AgentRun | undefined): string {
  const draft = agentDraftNote(run)
  return draft ? `${draft} · ${note}` : note
}

/** Markdown followed by a rule and the note as its own paragraph. */
export function markdownWithNote(markdown: string, note: string): string {
  return `${markdown}\n\n---\n\n${note}`
}

/** Plain text followed by the note as its own paragraph. */
export function plainTextWithNote(text: string, note: string): string {
  return `${text}\n\n${note}`
}
