import { describe, expect, test } from 'vitest'
import { buildCitationIndex } from './citations'
import type { SourceReference } from '../../types/api'

function source(fileName: string, cited: boolean): SourceReference {
  return { fileName, relevanceScore: 0.9, matchCount: 1, cited, indexedAt: null }
}

describe('buildCitationIndex', () => {
  test('numbers markers in order of first appearance and reuses numbers for repeats', () => {
    const content =
      'Erstens【source: aa#0 | a.md】, zweitens【source: bb#2 | b.md】und ' +
      'nochmals【source: aa#0 | a.md】.'

    const index = buildCitationIndex(content, [source('a.md', true), source('b.md', true)])

    expect(index.numberByKey.get('aa#0')).toBe(1)
    expect(index.numberByKey.get('bb#2')).toBe(2)
    expect(index.markerCount).toBe(2)
  })

  test('groups numbers of the same document into one row', () => {
    const content =
      'Eins【source: aa#0 | a.md】zwei【source: aa#1 | a.md】drei【source: bb#0 | b.md】'

    const index = buildCitationIndex(content, [source('a.md', true), source('b.md', true)])

    expect(index.docs).toHaveLength(2)
    expect(index.docs[0]).toMatchObject({ fileName: 'a.md', numbers: [1, 2] })
    expect(index.docs[1]).toMatchObject({ fileName: 'b.md', numbers: [3] })
    expect(index.docs[0].source?.cited).toBe(true)
  })

  test('splits uncited sources out and appends cited sources without markers numberless', () => {
    const content = 'Nur eine Stelle【source: aa#0 | a.md】.'

    const index = buildCitationIndex(content, [
      source('a.md', true),
      source('geprueft-aber-unzitiert.md', false),
      source('zitiert-ohne-marker.md', true),
    ])

    expect(index.uncited.map((s) => s.fileName)).toEqual(['geprueft-aber-unzitiert.md'])
    expect(index.docs.map((d) => d.fileName)).toEqual(['a.md', 'zitiert-ohne-marker.md'])
    expect(index.docs[1].numbers).toEqual([])
  })

  test('keeps a marker without matching source as a numbered row without metadata', () => {
    const index = buildCitationIndex('Text【source: xx#0 | verwaist.md】', [])

    expect(index.docs).toEqual([{ fileName: 'verwaist.md', numbers: [1], source: undefined }])
  })

  test('maps every number to its document row for the in-text anchors', () => {
    const content = 'A【source: aa#0 | a.md】B【source: aa#1 | a.md】C【source: bb#0 | b.md】'

    const index = buildCitationIndex(content, [source('a.md', true), source('b.md', true)])

    expect(index.docIndexByNumber.get(1)).toBe(0)
    expect(index.docIndexByNumber.get(2)).toBe(0)
    expect(index.docIndexByNumber.get(3)).toBe(1)
  })

  test('returns an empty index for content without markers and no sources', () => {
    const index = buildCitationIndex('Nur Text.', undefined)

    expect(index.markerCount).toBe(0)
    expect(index.docs).toEqual([])
    expect(index.uncited).toEqual([])
  })
})
