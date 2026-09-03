import { describe, expect, it } from 'vitest'
import { EMPTY_CONFLUENCE_VALUES } from './confluenceSource'
import {
  deriveLibrarySourceConfigPayload,
  sameLibrarySourceOrigin,
  validateLibrarySourceFields,
} from './librarySourceConfig'

const generic = {
  sourcePath: ' /data/dokumente ',
  sourceUrl: ' https://docs.example/ ',
  sourceProxy: ' proxy.example:8080 ',
  sourceCredentials: ' user:pw ',
  sourceInsecureSsl: true,
}

describe('deriveLibrarySourceConfigPayload', () => {
  it('sends only the fields of the chosen type for the generic sources', () => {
    expect(deriveLibrarySourceConfigPayload('FILESYSTEM', generic)).toEqual({
      sourcePath: '/data/dokumente',
      sourceUrl: undefined,
      sourceProxy: undefined,
      sourceCredentials: undefined,
      sourceInsecureSsl: false,
    })
    expect(deriveLibrarySourceConfigPayload('HTTP_DIRECTORY', generic)).toEqual({
      sourcePath: undefined,
      sourceUrl: 'https://docs.example/',
      sourceProxy: 'proxy.example:8080',
      sourceCredentials: 'user:pw',
      sourceInsecureSsl: true,
    })
  })

  it('maps a Confluence configuration to edition, joined credentials and the selection (ADR-0023)', () => {
    expect(
      deriveLibrarySourceConfigPayload('CONFLUENCE', {
        ...generic,
        confluence: {
          ...EMPTY_CONFLUENCE_VALUES,
          sourceUrl: ' https://behoerde.atlassian.net ',
          sourceProxy: '',
          edition: 'CLOUD',
          email: 'dienst@behoerde.example',
          token: 'tok',
          credentialsVerified: true,
          spaces: [
            { key: 'BAU', name: 'Bauamt' },
            { key: 'HR', name: undefined as unknown as null },
          ],
        },
      }),
    ).toEqual({
      sourceUrl: 'https://behoerde.atlassian.net',
      sourceProxy: undefined,
      sourceCredentials: 'dienst@behoerde.example:tok',
      sourceInsecureSsl: false,
      confluenceEdition: 'CLOUD',
      confluenceSpaces: [
        { key: 'BAU', name: 'Bauamt' },
        { key: 'HR', name: null },
      ],
    })
  })

  it('omits the credentials when no token was typed, so the stored ones stand on update', () => {
    const payload = deriveLibrarySourceConfigPayload('CONFLUENCE', {
      ...generic,
      confluence: {
        ...EMPTY_CONFLUENCE_VALUES,
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        edition: 'DATA_CENTER',
        credentialsVerified: true,
        spaces: [{ key: 'BAU', name: 'Bauamt' }],
      },
    })
    expect(payload.sourceCredentials).toBeUndefined()
    expect(payload.confluenceEdition).toBe('DATA_CENTER')
  })
})

describe('validateLibrarySourceFields', () => {
  it('delegates Confluence to the staged validation', () => {
    expect(
      validateLibrarySourceFields('CONFLUENCE', {
        sourcePath: '',
        sourceUrl: '',
        confluence: { ...EMPTY_CONFLUENCE_VALUES, sourceUrl: 'https://wiki.example' },
      }),
    ).toBe('Bitte zuerst die Edition erkennen lassen („Edition erkennen“)')
    expect(validateLibrarySourceFields('CONFLUENCE', { sourcePath: '', sourceUrl: '' })).toBe(
      'Adresse der Confluence-Instanz ist erforderlich',
    )
  })

  it('keeps the generic checks for path and URL sources', () => {
    expect(
      validateLibrarySourceFields('FILESYSTEM', { sourcePath: 'relativ', sourceUrl: '' }),
    ).toBe('Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente')
    expect(
      validateLibrarySourceFields('HTTP_DIRECTORY', { sourcePath: '', sourceUrl: 'docs.example' }),
    ).toBe('Adresse (URL) muss mit http:// oder https:// beginnen')
    expect(validateLibrarySourceFields('UPLOAD', { sourcePath: '', sourceUrl: '' })).toBeNull()
  })
})

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
