import { describe, expect, it } from 'vitest'
import { containsQuote, markedItems, normalizedText } from './quoteMatch'

const ITEMS = [
  '2 Module',
  'Das Projekt besteht aus mehreren Modulen.',
  'Das Modul indexing verwaltet die Dokumentenaufnahme: es liest Dateien',
  'aus den Wissensbibliotheken, zerlegt sie in Abschnitte und speichert',
]

const SINGLE = { startsHere: true, endsHere: true }

describe('normalizedText', () => {
  it('drops all whitespace and lower-cases', () => {
    expect(normalizedText('  Das\n Modul\tINDEXING ')).toBe('dasmodulindexing')
  })
})

describe('markedItems', () => {
  it('marks the items from the start quote to the end quote across item boundaries', () => {
    expect(
      markedItems(ITEMS, 'das modul  indexing', 'Wissensbibliotheken, zerlegt', SINGLE),
    ).toEqual([2, 3])
  })

  it('marks a start page from its quote to the end of the page', () => {
    expect(
      markedItems(ITEMS, 'Projekt besteht', 'nicht hier', { startsHere: true, endsHere: false }),
    ).toEqual([1, 3])
  })

  it('marks an end page from its top to the end quote', () => {
    expect(
      markedItems(ITEMS, 'nicht hier', 'besteht aus mehreren', {
        startsHere: false,
        endsHere: true,
      }),
    ).toEqual([0, 1])
  })

  it('marks a page in the middle of a passage whole', () => {
    expect(markedItems(ITEMS, 'x', 'y', { startsHere: false, endsHere: false })).toEqual([0, 3])
  })

  it('falls back to the head of a start quote whose tail changed in the original', () => {
    const quote = 'Das Modul indexing verwaltet die geänderte Fassung'
    expect(markedItems(ITEMS, quote, undefined, SINGLE)).toEqual([2, 2])
  })

  it('marks nothing when the start quote is not on the page', () => {
    expect(markedItems(ITEMS, 'Ganz anderer Text', 'speichert', SINGLE)).toBeNull()
  })

  it('marks nothing on an end page whose end quote is missing', () => {
    expect(markedItems(ITEMS, null, 'fehlt', { startsHere: false, endsHere: true })).toBeNull()
    expect(markedItems(ITEMS, null, null, { startsHere: false, endsHere: true })).toBeNull()
  })

  it('finds a quote whose words the text layer split into separate items', () => {
    const items = ['Die Fund', 'stelle steht ', 'hier', ' und dort.']
    expect(markedItems(items, 'Fundstelle steht hier', 'dort.', SINGLE)).toEqual([0, 3])
  })

  it('marks nothing on an empty page', () => {
    expect(markedItems([], 'a', 'b', SINGLE)).toBeNull()
  })
})

describe('containsQuote', () => {
  it('matches the whole quote only, never a shortened probe', () => {
    expect(containsQuote(ITEMS, 'Das Modul indexing verwaltet die Dokumentenaufnahme')).toBe(true)
    expect(containsQuote(ITEMS, 'Das Modul indexing verwaltet die geänderte Fassung')).toBe(false)
    expect(containsQuote(ITEMS, '  ')).toBe(false)
  })
})
