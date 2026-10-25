import { describe, expect, it } from 'vitest'
import {
  EMPTY_ONEDRIVE_VALUES,
  oneDrivePayloadOf,
  oneDriveSettingsFromLibrary,
  oneDriveValuesOf,
  type OneDriveSourceValues,
} from './oneDriveSource'
import { oneDriveSetup } from '../components/setup/connectors/oneDriveSetup'
import type { SourceFormContext } from '../components/sources/types'

const context: SourceFormContext = {
  mode: 'create',
  sourceType: 'ONEDRIVE',
  idPrefix: 'od',
  credentialsStored: false,
  privateLibrary: true,
}

const found: OneDriveSourceValues = {
  ...EMPTY_ONEDRIVE_VALUES,
  driveId: 'b!onedriveMaier',
  driveName: 'OneDrive',
}

describe('oneDriveSource (ADR-0058)', () => {
  it('reads stored folders as plain ids and with their shown names', () => {
    const settings = oneDriveSettingsFromLibrary({
      sourceType: 'ONEDRIVE',
      sourceSettings: {
        drive: {
          driveId: 'b!a',
          name: 'OneDrive',
          folders: ['01X', { id: '01Y', name: 'Vorlagen / 2026' }, { name: 'ohne Kennung' }],
        },
        fullSyncIntervalDays: 3,
      },
    })

    expect(oneDriveValuesOf(settings)).toEqual({
      driveId: 'b!a',
      driveName: 'OneDrive',
      driveFoundWith: null,
      allFolders: false,
      folders: [
        { id: '01X', name: null },
        { id: '01Y', name: 'Vorlagen / 2026' },
      ],
      fullSyncIntervalDays: 3,
    })
    expect(oneDriveSettingsFromLibrary({ sourceType: 'SHAREPOINT', sourceSettings: {} })).toBe(null)
  })

  it('reads a OneDrive without folders as the whole OneDrive', () => {
    expect(oneDriveValuesOf({ drive: { driveId: 'b!a' } })).toMatchObject({
      driveId: 'b!a',
      allFolders: true,
      folders: [],
    })
  })

  it('sends the fixed Graph address, no secret, and no folders under „Alle Ordner“', () => {
    expect(
      oneDrivePayloadOf({
        ...found,
        allFolders: true,
        folders: [{ id: '01X', name: 'Vorlagen' }],
        fullSyncIntervalDays: 2,
      }),
    ).toEqual({
      sourceUrl: 'https://graph.microsoft.com',
      sourceSettings: {
        drive: { driveId: 'b!onedriveMaier', name: 'OneDrive' },
        fullSyncIntervalDays: 2,
      },
    })
  })

  it('sends the chosen folders, named ones as objects', () => {
    expect(
      oneDrivePayloadOf({
        ...found,
        allFolders: false,
        folders: [
          { id: '01X', name: null },
          { id: '01Y', name: 'Vorlagen' },
        ],
      }),
    ).toEqual({
      sourceUrl: 'https://graph.microsoft.com',
      sourceSettings: {
        drive: {
          driveId: 'b!onedriveMaier',
          name: 'OneDrive',
          folders: ['01X', { id: '01Y', name: 'Vorlagen' }],
        },
      },
    })
  })

  it('sends no settings while the OneDrive is not known yet', () => {
    expect(oneDrivePayloadOf(EMPTY_ONEDRIVE_VALUES)).toEqual({
      sourceUrl: 'https://graph.microsoft.com',
    })
  })
})

describe('oneDriveSetup', () => {
  const issues = (values: OneDriveSourceValues) => oneDriveSetup.issues(values, context)

  it('asks for the OneDrive of the connected account first', () => {
    expect(issues(EMPTY_ONEDRIVE_VALUES)).toEqual([{ field: 'drive', message: 'OneDrive fehlt' }])
    expect(oneDriveSetup.rowOf('drive')).toBe('connection')
  })

  it('takes the whole OneDrive without a folder', () => {
    expect(issues(found)).toEqual([])
  })

  it('asks for folders once only chosen ones are to be read', () => {
    expect(issues({ ...found, allFolders: false })).toEqual([
      { field: 'folders', message: 'Ordner fehlen' },
    ])
    expect(oneDriveSetup.rowOf('folders')).toBe('scope')
  })

  it('caps the folders at fifty and refuses a folder id the connector does not accept', () => {
    const many = Array.from({ length: 51 }, (_, i) => ({ id: `01F${i}`, name: null }))
    expect(issues({ ...found, allFolders: false, folders: many })).toEqual([
      { field: 'folders', message: 'Höchstens 50 Ordner' },
    ])
    expect(
      issues({ ...found, allFolders: false, folders: [{ id: '../x', name: 'Fremd' }] }),
    ).toEqual([
      {
        field: 'folders',
        message: 'Ein Ordner hat keine gültige Kennung; bitte neu aus der Auflistung wählen.',
      },
    ])
  })

  it('summarises the rows: the OneDrive, and all or the chosen folders by path', () => {
    const [connection, scope] = oneDriveSetup.sections
    expect(connection.summary(EMPTY_ONEDRIVE_VALUES, context)).toBeNull()
    expect(connection.summary(found, context)).toBe('OneDrive')
    expect(scope.summary(found, context)).toBe('Alle Ordner')
    expect(
      scope.summary(
        {
          ...found,
          allFolders: false,
          folders: [
            { id: '01X', name: 'Vorlagen / 2026' },
            { id: '01Y', name: null },
          ],
        },
        context,
      ),
    ).toEqual({ lead: '2 Ordner', items: ['Vorlagen / 2026', 'Ordner 01Y'] })
  })
})
