/** A spreadsheet read from CSV: the head row and the data rows, each with its record number. */
export interface CsvTable {
  head: string[]
  /** `number` is the record number the index gives the row (`rowFrom`/`rowTo` of its anchor). */
  rows: { number: number; cells: string[] }[]
}

/** In this order: on a tie the earlier separator wins, as in the index. */
const SEPARATORS = [',', ';', '\t'] as const
const SAMPLE_LINES = 20

class CsvSyntaxError extends Error {}

/**
 * Reads the bytes of a CSV file as the index does: UTF-8 without its byte order mark, and
 * Windows-1252 (German Excel's default) where the bytes are not valid UTF-8.
 */
export function decodeCsv(bytes: Uint8Array): string {
  const body =
    bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf ? bytes.subarray(3) : bytes
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(body)
  } catch {
    return new TextDecoder('windows-1252').decode(body)
  }
}

/**
 * Reads CSV the way the index reads it, so the record numbers match the anchors' rows: quoted
 * cells with doubled quotes and line breaks, cells trimmed, the separator among `,`, `;` and tab
 * that best explains the first non-blank lines. Every record counts, blank ones too, but blank
 * records are not shown; the first non-blank record is the head, unless it is the only one.
 */
export function parseCsv(text: string): CsvTable {
  const source = text.startsWith('﻿') ? text.slice(1) : text
  if (source.trim() === '') return { head: [], rows: [] }
  const records = readRecords(source, detectSeparator(source), { strict: false, skipEmpty: false })
  const filled = records
    .map((cells, index) => ({ number: index + 1, cells: cells.map((cell) => cell.trim()) }))
    .filter((record) => record.cells.some((cell) => cell !== ''))
  if (filled.length < 2) return { head: [], rows: filled }
  const [head, ...rows] = filled
  return { head: head.cells, rows }
}

/**
 * Scores each separator on the first {@link SAMPLE_LINES} non-blank lines: rows that agree with
 * the first row's column count weigh most, then that count; a separator that leaves one column or
 * breaks the quoting scores 0. Falls back to comma.
 */
function detectSeparator(text: string): string {
  const sample = text
    .split(/\r\n|\r|\n/)
    .filter((line) => line.trim() !== '')
    .slice(0, SAMPLE_LINES)
    .join('\n')
  let best: string = SEPARATORS[0]
  let bestScore = -1
  for (const separator of SEPARATORS) {
    const score = separatorScore(sample, separator)
    if (score > bestScore) {
      bestScore = score
      best = separator
    }
  }
  return best
}

function separatorScore(sample: string, separator: string): number {
  let records: string[][]
  try {
    records = readRecords(sample, separator, { strict: true, skipEmpty: true })
  } catch (error) {
    if (error instanceof CsvSyntaxError) return 0
    throw error
  }
  const columns = records[0]?.length ?? 0
  if (columns <= 1) return 0
  return records.filter((record) => record.length === columns).length * 1000 + columns
}

/**
 * RFC 4180 records. A quote opens a quoted cell only as its first character, elsewhere it is
 * text; blanks after a closing quote are dropped. `strict` rejects other text there and an
 * unclosed quote, as the index's parser does; `skipEmpty` drops empty lines instead of reading them as a record of one empty cell.
 */
function readRecords(
  source: string,
  separator: string,
  { strict, skipEmpty }: { strict: boolean; skipEmpty: boolean },
): string[][] {
  const records: string[][] = []
  let record: string[] = []
  let cell = ''
  let started = false
  let quoted = false
  let closed = false
  const endCell = () => {
    record.push(cell)
    cell = ''
    started = false
    closed = false
  }
  const endRecord = () => {
    endCell()
    if (!(skipEmpty && record.length === 1 && record[0] === '')) records.push(record)
    record = []
  }
  for (let i = 0; i < source.length; i++) {
    const char = source[i]
    if (quoted) {
      if (char === '"' && source[i + 1] === '"') {
        cell += '"'
        i++
      } else if (char === '"') {
        quoted = false
        closed = true
      } else {
        cell += char
      }
    } else if (char === separator) {
      endCell()
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && source[i + 1] === '\n') i++
      endRecord()
    } else if (closed) {
      if (strict && !/\s/.test(char)) throw new CsvSyntaxError('text after a closing quote')
      if (!/\s/.test(char)) cell += char
    } else if (char === '"' && !started) {
      quoted = true
      started = true
    } else {
      cell += char
      started = true
    }
  }
  if (quoted && strict) throw new CsvSyntaxError('unclosed quote')
  if (started || cell !== '' || record.length > 0) endRecord()
  return records
}
