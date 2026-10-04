import { describe, expect, it } from 'vitest'
import { mockSourceTypes } from '../../../mocks/libraryFixtures'
import { EMPTY_CONFLUENCE_VALUES } from '../../../utils/confluenceSource'
import { EMPTY_S3_VALUES } from '../../../utils/s3Source'
import { registeredSourceTypes, sourceRegistration } from './registry'
import type { SourceConfiguration, SourceFormContext } from './types'

const generic = {
  sourcePath: ' /data/dokumente ',
  sourceUrl: ' https://docs.example/ ',
  sourceProxy: ' proxy.example:8080 ',
  sourceCredentials: ' user:pw ',
  sourceInsecureSsl: true,
}

function configurationOf(type: string): SourceConfiguration<unknown> {
  const configuration = sourceRegistration(type)?.configuration
  if (!configuration) throw new Error(`no configuration for ${type}`)
  return configuration
}

function context(type: string, patch: Partial<SourceFormContext> = {}): SourceFormContext {
  return { mode: 'create', sourceType: type, idPrefix: 'test', credentialsStored: false, ...patch }
}

describe('the source registry (ADR-0038)', () => {
  it('registers every delivered type once, UPLOAD without a form', () => {
    expect(registeredSourceTypes).toEqual([
      'UPLOAD',
      'FILESYSTEM',
      'HTTP_DIRECTORY',
      'RSS_FEED',
      'CONFLUENCE',
      'S3',
      'GOOGLE_DRIVE',
      'NEXTCLOUD',
      'SMB',
    ])
    expect(sourceRegistration('UPLOAD')?.configuration).toBeNull()
    expect(sourceRegistration('PROBE')).toBeUndefined()
  })

  it('has the mock backend list every registered type exactly once, ordered by key', () => {
    const keys = mockSourceTypes.map((descriptor) => descriptor.type)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys).toEqual([...keys].sort())
    expect([...keys].sort()).toEqual([...registeredSourceTypes].sort())
    const smb = mockSourceTypes.find((descriptor) => descriptor.type === 'SMB')
    expect(smb?.profileSupport).toBe('OPTIONAL')
    expect(smb?.serverAddress.schemes).toEqual(['smb'])
    expect(
      mockSourceTypes.find((descriptor) => descriptor.type === 'GOOGLE_DRIVE')?.profileSupport,
    ).toBe('OPTIONAL')
    expect(
      mockSourceTypes.find((descriptor) => descriptor.type === 'FILESYSTEM')?.profileSupport,
    ).toBe('FORBIDDEN')
  })

  it('sends only the fields of the chosen type for the generic sources', () => {
    expect(
      configurationOf('FILESYSTEM').toPayload({
        ...generic,
        excludePatterns: ' Archiv/** \n\n**/*.tmp\n',
      }),
    ).toEqual({
      sourcePath: '/data/dokumente',
      sourceInsecureSsl: false,
      sourceSettings: { excludePatterns: ['Archiv/**', '**/*.tmp'] },
    })
    expect(configurationOf('HTTP_DIRECTORY').toPayload(generic)).toEqual({
      sourceUrl: 'https://docs.example/',
      sourceProxy: 'proxy.example:8080',
      sourceCredentials: 'user:pw',
      sourceInsecureSsl: true,
    })
  })

  it('keeps the generic checks for path and URL sources', () => {
    expect(
      configurationOf('FILESYSTEM').validate(
        { ...generic, sourcePath: 'relativ', excludePatterns: '' },
        context('FILESYSTEM'),
      ),
    ).toBe('Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente')
    expect(
      configurationOf('FILESYSTEM').validate(
        { ...generic, excludePatterns: '/data/Archiv/**' },
        context('FILESYSTEM'),
      ),
    ).toMatch(/relativ zum Verzeichnispfad/)
    expect(
      configurationOf('HTTP_DIRECTORY').validate(
        { ...generic, sourceUrl: 'docs.example' },
        context('HTTP_DIRECTORY'),
      ),
    ).toBe('Adresse (URL) muss mit http:// oder https:// beginnen')
  })

  it('maps a Confluence configuration to edition, joined credentials and the selection (ADR-0023)', () => {
    expect(
      configurationOf('CONFLUENCE').toPayload({
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
      }),
    ).toEqual({
      sourceUrl: 'https://behoerde.atlassian.net',
      sourceProxy: undefined,
      sourceCredentials: 'dienst@behoerde.example:tok',
      sourceInsecureSsl: false,
      sourceSettings: {
        edition: 'CLOUD',
        spaces: [
          { key: 'BAU', name: 'Bauamt' },
          { key: 'HR', name: null },
        ],
      },
    })
  })

  it('omits the Confluence credentials when no token was typed, so the stored ones stand', () => {
    const payload = configurationOf('CONFLUENCE').toPayload({
      ...EMPTY_CONFLUENCE_VALUES,
      sourceUrl: 'https://wiki.behoerde.example/confluence',
      edition: 'DATA_CENTER',
      credentialsVerified: true,
      spaces: [{ key: 'BAU', name: 'Bauamt' }],
    })
    expect(payload.sourceCredentials).toBeUndefined()
    expect(payload.sourceSettings?.edition).toBe('DATA_CENTER')
  })

  it('delegates Confluence to the staged validation', () => {
    const confluence = configurationOf('CONFLUENCE')
    expect(
      confluence.validate(
        { ...EMPTY_CONFLUENCE_VALUES, sourceUrl: 'https://wiki.example' },
        context('CONFLUENCE'),
      ),
    ).toBe('Bitte zuerst die Edition erkennen lassen („Edition erkennen“)')
    expect(confluence.validate(EMPTY_CONFLUENCE_VALUES, context('CONFLUENCE'))).toBe(
      'Adresse der Confluence-Instanz ist erforderlich',
    )
  })

  describe('S3 (#1377, ADR-0027)', () => {
    const s3 = {
      ...EMPTY_S3_VALUES,
      sourceUrl: ' https://minio.intern.example:9000 ',
      sourceProxy: ' proxy.intern:3128 ',
      accessKey: 'AKIAEXAMPLE',
      secretKey: 'geheim',
      scopes: [{ bucket: 'dokumente', prefix: '2025' }],
      excludePatterns: '**/~*',
    }

    it('derives endpoint, key and settings and nothing of the other types', () => {
      expect(configurationOf('S3').toPayload(s3)).toEqual({
        sourceUrl: 'https://minio.intern.example:9000',
        sourceProxy: 'proxy.intern:3128',
        sourceCredentials: 'AKIAEXAMPLE:geheim',
        sourceInsecureSsl: false,
        sourceSettings: {
          region: 'us-east-1',
          pathStyle: true,
          scopes: [{ bucket: 'dokumente', prefix: '2025/' }],
          includePatterns: [],
          excludePatterns: ['**/~*'],
        },
      })
    })

    it('validates through the S3 stages and lets a stored key stand on the same endpoint', () => {
      const s3Config = configurationOf('S3')
      const keyless = { ...s3, accessKey: '', secretKey: '' }
      expect(s3Config.validate(s3, context('S3'))).toBeNull()
      expect(s3Config.validate(keyless, context('S3'))).toBe('Access Key ist erforderlich')
      expect(
        s3Config.validate(
          keyless,
          context('S3', {
            mode: 'edit',
            credentialsStored: true,
            originalSourceUrl: 'https://minio.intern.example:9000',
          }),
        ),
      ).toBeNull()
      expect(s3Config.validate(EMPTY_S3_VALUES, context('S3'))).toMatch(/Endpoint/)
    })
  })
})
