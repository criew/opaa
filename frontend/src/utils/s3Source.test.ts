import { describe, expect, it } from 'vitest'
import {
  EMPTY_S3_VALUES,
  derivedS3Endpoint,
  isValidS3BucketName,
  normalizeS3Prefix,
  s3CredentialsOf,
  s3ProviderTemplate,
  s3ScopesOverlap,
  s3SettingsOf,
  s3ValuesFromSettings,
  validateS3Values,
  type S3SourceValues,
} from './s3Source'

const complete: S3SourceValues = {
  ...EMPTY_S3_VALUES,
  sourceUrl: 'https://minio.intern.example:9000',
  accessKey: 'AKIAEXAMPLE',
  secretKey: 'geheim',
  scopes: [
    { bucket: 'protokolle', prefix: '/2025/protokolle' },
    { bucket: 'satzungen', prefix: '' },
  ],
  includePatterns: '**/*.pdf\n\n  **/*.docx  ',
}

describe('s3ProviderTemplate / derivedS3Endpoint (ADR-0027, Entscheidung 10)', () => {
  it('prefills AWS and Hetzner from the region and MinIO with path-style', () => {
    expect(s3ProviderTemplate('AWS')).toEqual({
      provider: 'AWS',
      region: 'eu-central-1',
      pathStyle: false,
      sourceUrl: 'https://s3.eu-central-1.amazonaws.com',
    })
    expect(s3ProviderTemplate('HETZNER').sourceUrl).toBe('https://fsn1.your-objectstorage.com')
    expect(s3ProviderTemplate('MINIO')).toEqual({
      provider: 'MINIO',
      region: 'us-east-1',
      pathStyle: true,
      sourceUrl: '',
    })
    expect(derivedS3Endpoint('AWS', ' eu-west-1 ')).toBe('https://s3.eu-west-1.amazonaws.com')
    expect(derivedS3Endpoint('MINIO', 'us-east-1')).toBeNull()
  })
})

describe('s3CredentialsOf / s3SettingsOf', () => {
  it('joins the key into the stored form and leaves stored credentials alone when empty', () => {
    expect(s3CredentialsOf(complete)).toBe('AKIAEXAMPLE:geheim')
    expect(s3CredentialsOf({ ...complete, sessionToken: ' tok ' })).toBe('AKIAEXAMPLE:geheim:tok')
    expect(s3CredentialsOf({ ...complete, accessKey: '', secretKey: '' })).toBeUndefined()
  })

  it('normalises prefixes and patterns into the API shape', () => {
    expect(s3SettingsOf(complete)).toEqual({
      region: 'us-east-1',
      pathStyle: true,
      scopes: [
        { bucket: 'protokolle', prefix: '2025/protokolle/' },
        { bucket: 'satzungen', prefix: null },
      ],
      includePatterns: ['**/*.pdf', '**/*.docx'],
      excludePatterns: [],
    })
    expect(s3SettingsOf({ ...complete, region: ' ' }).region).toBeNull()
    expect(normalizeS3Prefix('//a/b')).toBe('a/b/')
    expect(normalizeS3Prefix('  ')).toBe('')
  })

  it('reads a stored configuration back and recognises the provider from the endpoint', () => {
    const values = s3ValuesFromSettings('https://s3.eu-central-1.amazonaws.com', null, false, {
      region: 'eu-central-1',
      pathStyle: false,
      scopes: [{ bucket: 'dokumente', prefix: '2025/' }],
      includePatterns: ['**/*.pdf'],
      excludePatterns: null,
    })
    expect(values.provider).toBe('AWS')
    expect(values.scopes).toEqual([{ bucket: 'dokumente', prefix: '2025/' }])
    expect(values.includePatterns).toBe('**/*.pdf')
    expect(s3ValuesFromSettings('https://minio.intern:9000', null, null, null).provider).toBe(
      'MINIO',
    )
    expect(
      s3ValuesFromSettings('https://fsn1.your-objectstorage.com', null, null, null).provider,
    ).toBe('HETZNER')
  })
})

describe('validateS3Values (ADR-0027, Entscheidung 2)', () => {
  it('names the stages in order', () => {
    expect(validateS3Values(undefined)).toMatch(/Endpoint/)
    expect(validateS3Values({ ...complete, sourceUrl: 'minio.intern' })).toMatch(/http:\/\//)
    expect(validateS3Values({ ...complete, accessKey: '' })).toBe('Access Key ist erforderlich')
    expect(validateS3Values({ ...complete, secretKey: '' })).toBe('Secret Key ist erforderlich')
    expect(validateS3Values({ ...complete, accessKey: 'a:b' })).toMatch(/Doppelpunkt/)
    expect(validateS3Values({ ...complete, region: 'eu central 1' })).toMatch(
      /Region „eu central 1“ ist ungültig/,
    )
    expect(validateS3Values({ ...complete, sessionToken: 'x'.repeat(490) })).toMatch(
      /zusammen höchstens 500 Zeichen/,
    )
    expect(validateS3Values({ ...complete, scopes: [{ bucket: '', prefix: '' }] })).toMatch(
      /mindestens einen Geltungsbereich/,
    )
    expect(
      validateS3Values({ ...complete, scopes: [{ bucket: 'Grossbuchstaben', prefix: '' }] }),
    ).toMatch(/Bucket-Name „Grossbuchstaben“ ist ungültig/)
    expect(
      validateS3Values({ ...complete, scopes: [{ bucket: 'dokumente', prefix: 'berichte,2025' }] }),
    ).toMatch(/enthält ein Komma/)
    expect(
      validateS3Values({ ...complete, scopes: [{ bucket: 'dokumente', prefix: 'archiv/../alt' }] }),
    ).toMatch(/enthält „\.\.“ als Segment/)
    expect(validateS3Values(complete)).toBeNull()
  })

  it('lets the key fields stay empty in edit mode with a stored key', () => {
    const stored = { ...complete, accessKey: '', secretKey: '' }
    expect(validateS3Values(stored)).toBe('Access Key ist erforderlich')
    expect(validateS3Values(stored, true)).toBeNull()
    expect(validateS3Values({ ...stored, accessKey: 'AKIA' }, true)).toBe(
      'Secret Key ist erforderlich',
    )
  })

  it('refuses overlapping scopes naming both and the per-library bound', () => {
    expect(
      validateS3Values({
        ...complete,
        scopes: [
          { bucket: 'dokumente', prefix: '2025' },
          { bucket: 'dokumente', prefix: '2025/q1/' },
        ],
      }),
    ).toBe(
      'Die Geltungsbereiche „dokumente/2025/“ und „dokumente/2025/q1/“ überschneiden sich; jedes Objekt darf nur in einem Bereich liegen.',
    )
    expect(s3ScopesOverlap({ bucket: 'a', prefix: '' }, { bucket: 'a', prefix: 'x/' })).toBe(true)
    expect(s3ScopesOverlap({ bucket: 'a', prefix: 'x/' }, { bucket: 'b', prefix: 'x/' })).toBe(
      false,
    )
    expect(
      validateS3Values({
        ...complete,
        scopes: Array.from({ length: 51 }, (_v, i) => ({ bucket: `bucket-${i}`, prefix: '' })),
      }),
    ).toBe('Höchstens 50 Geltungsbereiche je Bibliothek')
  })

  it('checks the patterns', () => {
    expect(validateS3Values({ ...complete, includePatterns: '*.pdf\n*.pdf' })).toBe(
      'Einschlussmuster: ein Muster ist mehrfach angegeben',
    )
    expect(validateS3Values({ ...complete, excludePatterns: 'x'.repeat(256) })).toMatch(
      /Ausschlussmuster: ein Muster darf höchstens 255 Zeichen/,
    )
  })

  it('applies the bucket naming rules of the store', () => {
    expect(isValidS3BucketName('dokumente-2025')).toBe(true)
    expect(isValidS3BucketName('ab')).toBe(false)
    expect(isValidS3BucketName('doc..umente')).toBe(false)
    expect(isValidS3BucketName('192.168.0.1')).toBe(false)
    expect(isValidS3BucketName('-dokumente')).toBe(false)
  })
})
