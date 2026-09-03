import { describe, expect, it } from 'vitest'
import {
  confluenceCredentialsOf,
  EMPTY_CONFLUENCE_VALUES,
  MAX_CONFLUENCE_SPACES,
  validateConfluenceValues,
  type ConfluenceSourceValues,
} from './confluenceSource'

const verified: ConfluenceSourceValues = {
  ...EMPTY_CONFLUENCE_VALUES,
  sourceUrl: 'https://wiki.behoerde.example/confluence',
  edition: 'DATA_CENTER',
  token: 'pat',
  credentialsVerified: true,
  spaces: [{ key: 'BAU', name: 'Bauamt' }],
}

describe('confluenceCredentialsOf (ADR-0023, Entscheidung 3)', () => {
  it('joins e-mail and token for Cloud and trims both', () => {
    expect(
      confluenceCredentialsOf({
        ...verified,
        edition: 'CLOUD',
        email: ' dienst@behoerde.example ',
        token: ' tok ',
      }),
    ).toBe('dienst@behoerde.example:tok')
  })

  it('passes the bare token for Data Center', () => {
    expect(confluenceCredentialsOf({ ...verified, token: ' pat-1 ' })).toBe('pat-1')
  })

  it('returns undefined without a token so the stored credentials stand', () => {
    expect(confluenceCredentialsOf({ ...verified, token: '   ' })).toBeUndefined()
    expect(
      confluenceCredentialsOf({ ...verified, edition: 'CLOUD', email: 'x@y.example', token: '' }),
    ).toBeUndefined()
  })
})

describe('validateConfluenceValues names the next missing stage', () => {
  it('starts with the address', () => {
    expect(validateConfluenceValues(undefined)).toBe(
      'Adresse der Confluence-Instanz ist erforderlich',
    )
    expect(validateConfluenceValues({ ...verified, sourceUrl: '  ' })).toBe(
      'Adresse der Confluence-Instanz ist erforderlich',
    )
    expect(validateConfluenceValues({ ...verified, sourceUrl: 'wiki.example' })).toBe(
      'Adresse der Confluence-Instanz muss mit http:// oder https:// beginnen',
    )
  })

  it('then the detected edition, the verified credentials and the selection - in that order', () => {
    expect(
      validateConfluenceValues({
        ...verified,
        edition: null,
        credentialsVerified: false,
        spaces: [],
      }),
    ).toBe('Bitte zuerst die Edition erkennen lassen („Edition erkennen“)')
    expect(validateConfluenceValues({ ...verified, credentialsVerified: false, spaces: [] })).toBe(
      'Bitte die Zugangsdaten mit „Verbindung testen“ prüfen, bevor Sie fortfahren',
    )
    expect(validateConfluenceValues({ ...verified, spaces: [] })).toBe(
      'Bitte mindestens einen Space auswählen',
    )
  })

  it('rejects more spaces than the backend accepts, and passes a complete configuration', () => {
    const tooMany = Array.from({ length: MAX_CONFLUENCE_SPACES + 1 }, (_, i) => ({
      key: `S${i}`,
      name: null,
    }))
    expect(validateConfluenceValues({ ...verified, spaces: tooMany })).toBe(
      `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces je Bibliothek`,
    )
    expect(validateConfluenceValues(verified)).toBeNull()
  })
})
