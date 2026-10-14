import { describe, expect, it } from 'vitest'
import { documentSourceTypeShortLabel } from './sourceLabels'

describe('documentSourceTypeShortLabel (#1970)', () => {
  it('gives every source type a short badge form', () => {
    expect(documentSourceTypeShortLabel('S3')).toBe('S3')
    expect(documentSourceTypeShortLabel('HTTP_DIRECTORY')).toBe('Web')
    expect(documentSourceTypeShortLabel('RSS_FEED')).toBe('Feed')
    expect(documentSourceTypeShortLabel('UPLOAD')).toBe('Upload')
    expect(documentSourceTypeShortLabel('FILESYSTEM')).toBe('Dateisystem')
    expect(documentSourceTypeShortLabel('CONFLUENCE')).toBe('Confluence')
  })
})
