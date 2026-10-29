import { describe, expect, it } from 'vitest'
import { mockSourceTypes } from '../../mocks/libraryFixtures'
import { EMPTY_CONFLUENCE_VALUES } from '../../utils/confluenceSource'
import { EMPTY_S3_VALUES } from '../../utils/s3Source'
import { SOURCE_TYPE_DIRECTORY, sourceTypeEntry } from '@/features/connection'
import { registeredSourceTypes, sourceRegistration } from './registry'
import type { SourceConfiguration } from './types'

const generic = {
  sourcePath: ' /data/dokumente ',
  sourceUrl: ' https://docs.example/ ',
  sourceCredentials: ' user:pw ',
}

function configurationOf(type: string): SourceConfiguration<unknown> {
  const configuration = sourceRegistration(type)?.configuration
  if (!configuration) throw new Error(`no configuration for ${type}`)
  return configuration
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
      'SHAREPOINT',
      'ONEDRIVE',
      'NEXTCLOUD',
      'DROPBOX',
      'SMB',
    ])
    expect(sourceRegistration('UPLOAD')?.configuration).toBeNull()
    expect(sourceRegistration('PROBE')).toBeUndefined()
  })

  it('knows exactly the source types of the connection directory', () => {
    expect(Object.keys(SOURCE_TYPE_DIRECTORY).sort()).toEqual([...registeredSourceTypes].sort())
  })

  it.each(registeredSourceTypes)(
    'names and marks %s exactly as the connection directory does',
    (type) => {
      const entry = sourceTypeEntry(type)
      expect(entry).toBeDefined()
      expect(sourceRegistration(type)).toMatchObject({
        label: entry!.label,
        shortLabel: entry!.shortLabel,
        description: entry!.description,
        Icon: entry!.Icon,
      })
    },
  )

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
    // SharePoint signs in only through a profile, at the fixed Graph address (ADR-0047)
    expect(mockSourceTypes.find((descriptor) => descriptor.type === 'SHAREPOINT')).toMatchObject({
      profileSupport: 'REQUIRED',
      profileRequired: true,
      creatableWithOwnAddress: false,
      serverAddress: { fixed: 'https://graph.microsoft.com' },
      signIns: [{ method: 'CLIENT_CREDENTIALS' }],
    })
  })

  it.each(registeredSourceTypes.filter((type) => type !== 'UPLOAD'))(
    'notes a fixed address for %s exactly as the mock descriptors declare',
    (type) => {
      const descriptor = mockSourceTypes.find((d) => d.type === type)
      expect(Boolean(configurationOf(type).setup.fixedAddress)).toBe(
        Boolean(descriptor?.serverAddress.fixed),
      )
    },
  )

  describe('OneDrive (ADR-0058)', () => {
    const library = {
      sourceType: 'ONEDRIVE',
      sourceUrl: 'https://graph.microsoft.com',
      sourceSettings: {
        drive: {
          driveId: 'b!onedriveMaier',
          name: 'OneDrive',
          folders: ['01X', { id: '01Y', name: 'Vorlagen / 2026' }],
        },
        fullSyncIntervalDays: 3,
      },
    }

    it('is offered by the mock backend only through the connected account of a person', () => {
      expect(mockSourceTypes.find((descriptor) => descriptor.type === 'ONEDRIVE')).toMatchObject({
        profileSupport: 'REQUIRED',
        profileRequired: true,
        creatableWithOwnAddress: false,
        browsable: true,
        serverAddress: { fixed: 'https://graph.microsoft.com' },
        signIns: [{ method: 'OAUTH', ownerships: ['PERSON'] }],
      })
    })

    it('reads a stored library back unchanged and names it after a single folder', () => {
      const oneDrive = configurationOf('ONEDRIVE')
      const values = oneDrive.fromLibrary(library)
      expect(oneDrive.toPayload(values)).toEqual({
        sourceUrl: 'https://graph.microsoft.com',
        sourceSettings: library.sourceSettings,
      })
      expect(oneDrive.nameFromSource(values)).toBe('')
      expect(
        oneDrive.nameFromSource(
          oneDrive.fromLibrary({
            ...library,
            sourceSettings: {
              drive: { driveId: 'b!a', folders: [{ id: '01Y', name: 'Vorlagen / 2026' }] },
            },
          }),
        ),
      ).toBe('2026')
      expect(oneDrive.nameFromSource(oneDrive.empty)).toBe('')
    })

    it('runs in two modes and names no scope note', () => {
      const configuration = configurationOf('ONEDRIVE')
      expect(configuration.fullSyncRhythm).toBe(true)
      expect(configuration.scopeNoteId).toBeUndefined()
    })
  })

  describe('SharePoint (ADR-0047)', () => {
    const library = {
      sourceType: 'SHAREPOINT',
      sourceUrl: 'https://graph.microsoft.com',
      sourceSettings: {
        libraries: [
          { driveId: 'b!a', name: 'Dokumente (Rathaus)', folders: [{ id: '01X', name: 'Akten' }] },
          { driveId: 'b!b', name: 'Satzungen (Rathaus)' },
        ],
        fullSyncIntervalDays: 3,
      },
    }

    it('reads a stored library back unchanged', () => {
      const sharePoint = configurationOf('SHAREPOINT')
      const values = sharePoint.fromLibrary(library)
      expect(sharePoint.toPayload(values)).toEqual({
        sourceUrl: 'https://graph.microsoft.com',
        sourceSettings: library.sourceSettings,
      })
      expect(sharePoint.nameFromSource(values)).toBe('')
    })
  })

  it('sends only the fields of the chosen type for the generic sources', () => {
    expect(
      configurationOf('FILESYSTEM').toPayload({
        ...generic,
        excludePatterns: ' Archiv/** \n\n**/*.tmp\n',
      }),
    ).toEqual({
      sourcePath: '/data/dokumente',
      sourceSettings: { excludePatterns: ['Archiv/**', '**/*.tmp'] },
    })
    expect(configurationOf('HTTP_DIRECTORY').toPayload(generic)).toEqual({
      sourceUrl: 'https://docs.example/',
      sourceCredentials: 'user:pw',
    })
  })

  it('maps a Confluence configuration to edition, joined credentials and the selection (ADR-0023)', () => {
    expect(
      configurationOf('CONFLUENCE').toPayload({
        ...EMPTY_CONFLUENCE_VALUES,
        allSpaces: false,
        sourceUrl: ' https://behoerde.atlassian.net ',
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
      sourceCredentials: 'dienst@behoerde.example:tok',
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

  describe('S3 (#1377, ADR-0027)', () => {
    const s3 = {
      ...EMPTY_S3_VALUES,
      allBuckets: false,
      sourceUrl: ' https://minio.intern.example:9000 ',
      accessKey: 'AKIAEXAMPLE',
      secretKey: 'geheim',
      scopes: [{ bucket: 'dokumente', prefix: '2025' }],
      excludePatterns: '**/~*',
    }

    it('derives endpoint, key and settings and nothing of the other types', () => {
      expect(configurationOf('S3').toPayload(s3)).toEqual({
        sourceUrl: 'https://minio.intern.example:9000',
        sourceCredentials: 'AKIAEXAMPLE:geheim',
        sourceSettings: {
          region: 'us-east-1',
          pathStyle: true,
          scopes: [{ bucket: 'dokumente', prefix: '2025/' }],
          includePatterns: [],
          excludePatterns: ['**/~*'],
        },
      })
    })
  })
})
