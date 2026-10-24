import { describe, expect, it } from 'vitest'
import {
  EMPTY_SHAREPOINT_VALUES,
  sharePointFolderPath,
  sharePointLibrariesOf,
  sharePointLibraryName,
  sharePointPayloadOf,
  sharePointSettingsFromLibrary,
  sharePointIdsValid,
  type SharePointLibraryChoice,
} from './sharePointSource'
import { sharePointSetup } from '../components/setup/connectors/sharePointSetup'
import type { SourceFormContext } from '../components/sources/types'

const context: SourceFormContext = {
  mode: 'edit',
  sourceType: 'SHAREPOINT',
  idPrefix: 'sp',
  credentialsStored: false,
}

describe('sharePointSource (ADR-0047)', () => {
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

  it('accepts only Graph ids the connector takes', () => {
    expect(sharePointIdsValid({ driveId: 'b!a_-9', folders: [{ id: '01X', name: null }] })).toBe(
      true,
    )
    expect(sharePointIdsValid({ driveId: 'b!a', folders: [{ id: '../x', name: null }] })).toBe(
      false,
    )
    expect(sharePointIdsValid({ driveId: 'd 1', folders: [] })).toBe(false)
    expect(sharePointIdsValid({ driveId: '', folders: [] })).toBe(false)
  })

  it('refuses no library, more than fifty and ids the connector would refuse, at the field', () => {
    const issues = (libraries: SharePointLibraryChoice[]) =>
      sharePointSetup.issues({ libraries, fullSyncIntervalDays: null }, context)
    expect(issues(EMPTY_SHAREPOINT_VALUES.libraries)).toEqual([
      { field: 'libraries', message: 'Dokumentbibliotheken fehlen' },
    ])
    const many = Array.from({ length: 51 }, (_, i) => ({
      driveId: `b!d${i}`,
      name: null,
      folders: [],
    }))
    expect(issues(many)).toEqual([
      { field: 'libraries', message: 'Höchstens 50 Dokumentbibliotheken' },
    ])
    expect(issues([{ driveId: 'b!a', name: null, folders: [{ id: '../x', name: null }] }])).toEqual(
      [{ field: 'libraries', message: expect.stringMatching(/keine gültige Kennung/) }],
    )
    expect(issues([{ driveId: 'b!a', name: null, folders: [] }])).toEqual([])
  })

  it('words names and paths', () => {
    expect(sharePointLibraryName('Dokumente', 'Bauamt')).toBe('Dokumente (Bauamt)')
    expect(sharePointLibraryName(null, 'Bauamt')).toBe('Bauamt')
    expect(sharePointLibraryName('x'.repeat(300), null)).toHaveLength(200)
    expect(sharePointFolderPath(['Akten'], '2026')).toBe('Akten / 2026')
  })
})
