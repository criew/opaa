import { describe, expect, it } from 'vitest'
import {
  EMPTY_SHAREPOINT_VALUES,
  sharePointCoverageLabel,
  sharePointFolderPath,
  sharePointKeyOf,
  sharePointLibrariesOf,
  sharePointLibraryName,
  sharePointPayloadOf,
  sharePointSettingsFromLibrary,
  validateSharePointValues,
} from './sharePointSource'

describe('sharePointSource (ADR-0040, Nachtrag „SharePoint“)', () => {
  it('reads stored folders as plain ids and with their shown names', () => {
    const settings = sharePointSettingsFromLibrary({
      sourceType: 'SHAREPOINT',
      sourceSettings: {
        libraries: [
          {
            driveId: 'b!a',
            name: 'Dokumente (Rathaus)',
            folders: ['01X', { id: '01Y', name: 'Akten / 2026' }],
          },
          { driveId: 'b!b' },
          { name: 'ohne Laufwerk' },
        ],
        fullSyncIntervalDays: 3,
      },
    })

    expect(sharePointLibrariesOf(settings)).toEqual([
      {
        driveId: 'b!a',
        name: 'Dokumente (Rathaus)',
        folders: [
          { id: '01X', name: null },
          { id: '01Y', name: 'Akten / 2026' },
        ],
      },
      { driveId: 'b!b', name: null, folders: [] },
    ])
    expect(sharePointSettingsFromLibrary({ sourceType: 'GOOGLE_DRIVE', sourceSettings: {} })).toBe(
      null,
    )
  })

  it('sends the fixed Graph address, no secret, and named folders as objects', () => {
    expect(
      sharePointPayloadOf({
        libraries: [
          {
            driveId: 'b!a',
            name: 'Dokumente (Rathaus)',
            folders: [
              { id: '01X', name: null },
              { id: '01Y', name: 'Akten' },
            ],
          },
          { driveId: 'b!b', name: null, folders: [] },
        ],
        fullSyncIntervalDays: 2,
      }),
    ).toEqual({
      sourceUrl: 'https://graph.microsoft.com',
      sourceInsecureSsl: false,
      sourceSettings: {
        libraries: [
          {
            driveId: 'b!a',
            name: 'Dokumente (Rathaus)',
            folders: ['01X', { id: '01Y', name: 'Akten' }],
          },
          { driveId: 'b!b' },
        ],
        fullSyncIntervalDays: 2,
      },
    })
  })

  it('refuses no library, more than fifty and ids the connector would refuse', () => {
    expect(validateSharePointValues(EMPTY_SHAREPOINT_VALUES)).toBe(
      'Bitte mindestens eine Dokumentbibliothek wählen.',
    )
    const many = Array.from({ length: 51 }, (_, i) => ({
      driveId: `b!d${i}`,
      name: null,
      folders: [],
    }))
    expect(validateSharePointValues({ libraries: many, fullSyncIntervalDays: null })).toBe(
      'Höchstens 50 Dokumentbibliotheken sind möglich.',
    )
    expect(
      validateSharePointValues({
        libraries: [{ driveId: 'b!a', name: null, folders: [{ id: '../x', name: null }] }],
        fullSyncIntervalDays: null,
      }),
    ).toMatch(/keine gültige Kennung/)
    expect(
      validateSharePointValues({
        libraries: [{ driveId: 'b!a', name: null, folders: [] }],
        fullSyncIntervalDays: null,
      }),
    ).toBeNull()
  })

  it('words names, paths, coverage and listing keys', () => {
    expect(sharePointLibraryName('Dokumente', 'Bauamt')).toBe('Dokumente (Bauamt)')
    expect(sharePointLibraryName(null, 'Bauamt')).toBe('Bauamt')
    expect(sharePointLibraryName('x'.repeat(300), null)).toHaveLength(200)
    expect(sharePointFolderPath(['Akten'], '2026')).toBe('Akten / 2026')
    expect(
      sharePointCoverageLabel({ driveId: 'b!a', name: null, folders: [{ id: '01X', name: null }] }),
    ).toBe('Ordner: Ordner 01X')
    expect(sharePointCoverageLabel({ driveId: 'b!a', name: null, folders: [] })).toBe(
      'ganze Dokumentbibliothek',
    )
    expect(sharePointKeyOf('site:host,a,b')).toEqual({ kind: 'site', id: 'host,a,b' })
    expect(sharePointKeyOf('drive:b!a')).toEqual({ kind: 'drive', id: 'b!a' })
    expect(sharePointKeyOf('myDrive')).toBeNull()
  })
})
