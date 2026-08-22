import { describe, expect, test } from 'vitest'
import { buildCitationIndex } from './citations'
import type { SourceReference } from '../../types/api'

function source(
  fileName: string,
  cited: boolean,
  documentId?: string,
  relevanceScore = 0.9,
): SourceReference {
  return {
    fileName,
    relevanceScore,
    matchCount: 1,
    cited,
    indexedAt: null,
    citationValid: true,
    documentId,
  }
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

  test('a marker outranks the cited flag - the text is the truth (#592)', () => {
    const index = buildCitationIndex('Beleg【source: aa#0 | doch-zitiert.md】', [
      source('doch-zitiert.md', false),
    ])

    expect(index.docs).toHaveLength(1)
    expect(index.docs[0].fileName).toBe('doch-zitiert.md')
    expect(index.docs[0].source?.fileName).toBe('doch-zitiert.md')
    expect(index.uncited).toEqual([])
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

  test('resolves markers by documentId, not last-wins by file name, for two same-named documents (PR #745 review)', () => {
    // Document A (higher relevance, cited) and document B (lower relevance, uncited) share a file
    // name but have distinct document ids - the backend now keeps both as separate SourceReference
    // rows (#739) instead of merging them into one.
    const docA = source('anlage.pdf', true, 'doc-a', 0.9)
    const docB = source('anlage.pdf', false, 'doc-b', 0.5)
    const content = 'Beleg【source: doc-a#0 | anlage.pdf】.'

    const index = buildCitationIndex(content, [docA, docB])

    expect(index.docs).toHaveLength(1)
    // The cited marker must resolve to document A's own metadata, not document B's (which a
    // fileName-keyed, last-wins map would return since B appears later in the sources array).
    expect(index.docs[0].source?.documentId).toBe('doc-a')
    expect(index.docs[0].source?.relevanceScore).toBe(0.9)
    expect(index.docs[0].documentId).toBe('doc-a')
    // Document B must not disappear - it stays visible as an uncited source.
    expect(index.uncited.map((s) => s.documentId)).toEqual(['doc-b'])
  })

  test('falls back to file name when a persisted legacy message carries no documentId', () => {
    // chat_messages.sources is a JSON snapshot (#590) - an older, persisted message may predate
    // #739 and carry no documentId at all. The file-name join must still work for those.
    const index = buildCitationIndex('Beleg【source: aa#0 | legacy.pdf】', [
      source('legacy.pdf', true, undefined),
    ])

    expect(index.docs).toHaveLength(1)
    expect(index.docs[0].source?.fileName).toBe('legacy.pdf')
  })

  test('returns an empty index for content without markers and no sources', () => {
    const index = buildCitationIndex('Nur Text.', undefined)

    expect(index.markerCount).toBe(0)
    expect(index.docs).toEqual([])
    expect(index.uncited).toEqual([])
  })
})
