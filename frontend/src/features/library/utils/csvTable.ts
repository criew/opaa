/** A spreadsheet read from CSV: the head row and the data rows, each with its record number. */
export interface CsvTable {
  head: string[]
  /** `number` counts records from 1 for the head row, as the index numbers rows. */
  rows: { number: number; cells: string[] }[]
}

const SEPARATORS = [';', ',', '\t'] as const

/**
 * Reads CSV per RFC 4180 (quoted cells, doubled quotes, line breaks inside quotes). The separator
 * is the one of `;`, `,` and tab that the first line uses most outside quotes. Empty lines are no
 * records, so the record numbers match the row numbers of the index.
 */
export function parseCsv(text: string): CsvTable {
  const source = text.startsWith('﻿') ? text.slice(1) : text
  const separator = detectSeparator(source)
  const records: string[][] = []
  let record: string[] = []
  let cell = ''
  let quoted = false
  const endRecord = () => {
    record.push(cell)
    if (record.length > 1 || record[0] !== '') records.push(record)
    record = []
    cell = ''
  }
  for (let i = 0; i < source.length; i++) {
    const char = source[i]
    if (quoted) {
      if (char === '"' && source[i + 1] === '"') {
        cell += '"'
        i++
      } else if (char === '"') {
        quoted = false
      } else {
        cell += char
      }
    } else if (char === '"') {
      quoted = true
    } else if (char === separator) {
      record.push(cell)
      cell = ''
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && source[i + 1] === '\n') i++
      endRecord()
    } else {
      cell += char
    }
  }
  if (cell !== '' || record.length > 0) endRecord()
  const [head = [], ...rest] = records
  return { head, rows: rest.map((cells, index) => ({ number: index + 2, cells })) }
}

function detectSeparator(text: string): string {
  let quoted = false
  const counts = new Map<string, number>(SEPARATORS.map((separator) => [separator, 0]))
  for (const char of text) {
    if (char === '"') quoted = !quoted
    else if (!quoted && (char === '\n' || char === '\r')) break
    else if (!quoted && counts.has(char)) counts.set(char, counts.get(char)! + 1)
  }
  let best: string = SEPARATORS[0]
  for (const separator of SEPARATORS) {
    if (counts.get(separator)! > counts.get(best)!) best = separator
  }
  return best
}
