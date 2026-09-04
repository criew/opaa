import { describe, expect, test } from 'vitest'
import {
  buildCitationIndex,
  describeMetadata,
  formatMetadataLine,
  metadataFilterMatchLabel,
} from './citations'
import type { SourceReference } from '../../types/api'

// #1066 (metadata-schema.md, Wirkstelle 3): the generic metadata line shared by the Fundstellen
// block and the Belegfenster - rendered from the backend's list without field knowledge.
describe('formatMetadataLine', () => {
  const base: SourceReference = {
    fileName: 'da.pdf',
    relevanceScore: 1,
    matchCount: 1,
    cited: true,
    indexedAt: null,
    citationValid: true,
  }

  test('is undefined without a list or with an empty one', () => {
    expect(formatMetadataLine(undefined)).toBeUndefined()
    expect(formatMetadataLine({ ...base, metadata: null })).toBeUndefined()
    expect(formatMetadataLine({ ...base, metadata: [] })).toBeUndefined()
    expect(describeMetadata({ ...base, metadata: [] })).toBeUndefined()
  })

  test('joins the display values in list order and marks derived ones', () => {
    const withMetadata: SourceReference = {
      ...base,
      metadata: [
        {
          fieldKey: 'document_type',
          label: 'Dokumentart',
          value: 'VERMERK',
          displayValue: 'Vermerk',
          origin: 'DERIVED',
        },
        {
          fieldKey: 'document_date',
          label: 'Datum/Stand',
          value: '2024-01-01',
          displayValue: '2024',
          origin: 'MANUAL',
          datePrecision: 'YEAR',
        },
      ],
    }

    expect(formatMetadataLine(withMetadata)).toBe('Vermerk (abgeleitet) · 2024')
    expect(describeMetadata(withMetadata)).toBe('Dokumentart: Vermerk, Datum/Stand: 2024')
  })
})

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

describe('metadataFilterMatchLabel (#1070)', () => {
  test('marks only a hit the Leerwert rule kept', () => {
    expect(
      metadataFilterMatchLabel({ ...source('a.pdf', true), metadataFilterMatch: 'NO_VALUE' }),
    ).toBe('ohne Angabe')
    expect(
      metadataFilterMatchLabel({ ...source('a.pdf', true), metadataFilterMatch: 'MATCHED' }),
    ).toBeUndefined()
    expect(metadataFilterMatchLabel(source('a.pdf', true))).toBeUndefined()
    expect(metadataFilterMatchLabel(undefined)).toBeUndefined()
  })
})

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

    expect(index.docs).toEqual([
      {
        fileName: 'verwaist.md',
        numbers: [1],
        source: undefined,
        // #1102: no source, hence no position in the pipeline's selection - such a row sorts last
        // in the Belegfenster instead of silently taking the top spot.
        sourceIndex: Number.MAX_SAFE_INTEGER,
        locations: [],
      },
    ])
  })

  test('carries the position of each row in the backend sources array (#1102)', () => {
    // The markers cite the second source first - the row order follows the text, but sourceIndex
    // keeps the pipeline's own selection order available to the Belegfenster.
    const content = 'A【source: doc-b#0 | b.md】 B【source: doc-a#0 | a.md】'

    const index = buildCitationIndex(content, [
      source('a.md', true, 'doc-a'),
      source('b.md', true, 'doc-b'),
    ])

    expect(index.docs.map((d) => [d.fileName, d.sourceIndex])).toEqual([
      ['b.md', 1],
      ['a.md', 0],
    ])
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

  test('resolves a footnote to the Fundort of the chunk its marker names (#667)', () => {
    const located: SourceReference = {
      ...source('a.md', true, 'aa'),
      chunkLocations: [
        { chunkIndex: 0, location: 'Abschn. 4 Fristen › 4.2 Fristsetzung' },
        { chunkIndex: 1, location: null },
        { chunkIndex: 2, location: 'S. 3' },
      ],
    }
    const content =
      'Eins【source: aa#0 | a.md】zwei【source: aa#1 | a.md】drei【source: aa#2 | a.md】' +
      'vier【source: aa#0 | a.md】'

    const index = buildCitationIndex(content, [located])

    expect(index.locationByNumber.get(1)).toBe('Abschn. 4 Fristen › 4.2 Fristsetzung')
    expect(index.locationByNumber.has(2)).toBe(false)
    expect(index.locationByNumber.get(3)).toBe('S. 3')
    expect(index.docs[0].locations).toEqual(['Abschn. 4 Fristen › 4.2 Fristsetzung', 'S. 3'])
  })

  test('leaves locations empty for a source without chunk locations (#667)', () => {
    const index = buildCitationIndex('Eins【source: aa#0 | a.md】', [source('a.md', true, 'aa')])

    expect(index.locationByNumber.size).toBe(0)
    expect(index.docs[0].locations).toEqual([])
  })
})
