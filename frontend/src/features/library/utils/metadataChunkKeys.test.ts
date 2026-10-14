import { describe, expect, it } from 'vitest'
import { isSchemaMetadataChunkKey, visibleChunkMetadata } from './metadataChunkKeys'

const stored = {
  location: 'Seite 2',
  document_id: 'doc-1',
  library_id: 'lib-1',
  doc_type: 'SATZUNG_ORDNUNG',
  doc_date: '2024-01-01',
  doc_date_precision: 'YEAR',
  lf_fassung: 'F2',
  lfp_stichtag: 'DAY',
  lfs_fassung: 'SET',
  ff_mail_from: 'poststelle@example.org',
  ffs_mail_from: 'SET',
}

describe('metadataChunkKeys', () => {
  it('names the core, library and format field keys as schema metadata, nothing else', () => {
    for (const key of [
      'doc_type',
      'doc_date',
      'doc_date_precision',
      'lf_fassung',
      'lfp_stichtag',
      'lfs_fassung',
      'ff_mail_from',
      'ffs_mail_from',
    ]) {
      expect(isSchemaMetadataChunkKey(key), key).toBe(true)
    }
    for (const key of ['location', 'document_id', 'library_id', 'file_name', 'pipeline_id']) {
      expect(isSchemaMetadataChunkKey(key), key).toBe(false)
    }
  })

  it('shows every key with metadata on', () => {
    expect(visibleChunkMetadata(stored, true).map(([key]) => key)).toEqual(
      Object.keys(stored).sort((a, b) => a.localeCompare(b)),
    )
  })

  it('leaves the schema metadata keys out with metadata off', () => {
    expect(visibleChunkMetadata(stored, false)).toEqual([
      ['document_id', 'doc-1'],
      ['library_id', 'lib-1'],
      ['location', 'Seite 2'],
    ])
  })
})
