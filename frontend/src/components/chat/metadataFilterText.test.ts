import { describe, expect, it } from 'vitest'
import {
  libraryFieldChipLabel,
  withoutDateWindow,
  withoutDocumentTypes,
  withoutLibraryField,
} from './metadataFilterText'
import type { MetadataFilter, MetadataFilterOptionsResponse } from '../../types/api'

const LIBRARY_A = '11111111-1111-1111-1111-111111111111'
const LIBRARY_B = '22222222-2222-2222-2222-222222222222'

const options: MetadataFilterOptionsResponse = {
  totalDocuments: 10,
  fields: [],
  documentTypes: [],
  libraryFields: [
    {
      libraryId: LIBRARY_A,
      libraryName: 'Satzungen',
      fieldKey: 'fassung',
      label: 'Fassung',
      type: 'SELECT',
      filledDocuments: 10,
      totalDocuments: 10,
      fillShare: 1,
      threshold: 0.75,
      offered: true,
      values: [{ code: 'F2026', label: 'Fassung 2026', documentCount: 4 }],
    },
    {
      libraryId: LIBRARY_B,
      libraryName: 'Projekte',
      fieldKey: 'fassung',
      label: 'Projektstand',
      type: 'SELECT',
      filledDocuments: 10,
      totalDocuments: 10,
      fillShare: 1,
      threshold: 0.75,
      offered: true,
      values: [{ code: 'F2026', label: 'Stand 2026', documentCount: 2 }],
    },
  ],
}

const filter: MetadataFilter = {
  documentTypes: ['VERMERK'],
  documentDateFrom: '2024-01-01',
  libraryFields: [
    { libraryId: LIBRARY_A, fieldKey: 'fassung', codes: ['F2026'] },
    { libraryId: LIBRARY_B, fieldKey: 'fassung', codes: ['F2026'] },
  ],
}

describe('metadataFilterText, library fields', () => {
  it('labels a condition with its own library, so the same key in two libraries stays apart', () => {
    expect(libraryFieldChipLabel(filter.libraryFields![0], options)).toBe('Fassung: Fassung 2026')
    expect(libraryFieldChipLabel(filter.libraryFields![1], options)).toBe(
      'Projektstand: Stand 2026',
    )
  })

  it('falls back to code and key when the scope no longer offers the field', () => {
    expect(
      libraryFieldChipLabel({ libraryId: LIBRARY_A, fieldKey: 'projekt', codes: ['X'] }, null),
    ).toBe('projekt: X')
  })

  it('labels a date window and an exact identifier', () => {
    expect(
      libraryFieldChipLabel(
        { libraryId: LIBRARY_A, fieldKey: 'fassung', dateFrom: '2024-01-01', dateTo: '2024-12-31' },
        options,
      ),
    ).toBe('Fassung: 01.01.2024 – 31.12.2024')
    expect(
      libraryFieldChipLabel({ libraryId: LIBRARY_A, fieldKey: 'paragraf', value: '§ 7' }, options),
    ).toBe('paragraf: § 7')
  })

  it('removes exactly one condition and carries the rest of the filter along', () => {
    const left = withoutLibraryField(filter, filter.libraryFields![0])

    expect(left.libraryFields).toEqual([filter.libraryFields![1]])
    expect(left.documentTypes).toEqual(['VERMERK'])
    expect(left.documentDateFrom).toBe('2024-01-01')
  })

  it('keeps the library conditions when a core-field chip is removed', () => {
    expect(withoutDocumentTypes(filter).libraryFields).toEqual(filter.libraryFields)
    expect(withoutDateWindow(filter).libraryFields).toEqual(filter.libraryFields)
  })
})
