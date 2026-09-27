import { describe, expect, it } from 'vitest'
import { sameLibrarySourceOrigin } from './librarySourceConfig'

describe('sameLibrarySourceOrigin', () => {
  it('compares scheme, host and port only', () => {
    expect(
      sameLibrarySourceOrigin('https://wiki.example/confluence', 'https://wiki.example/other'),
    ).toBe(true)
    expect(sameLibrarySourceOrigin('https://wiki.example', 'https://wiki.example:8443')).toBe(false)
    expect(sameLibrarySourceOrigin(null, 'https://wiki.example')).toBe(false)
    expect(sameLibrarySourceOrigin('https://wiki.example', 'nicht-eine-url')).toBe(false)
  })
})
