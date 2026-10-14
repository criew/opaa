import { describe, expect, it } from 'vitest'
import {
  EMPTY_DROPBOX_VALUES,
  MAX_DROPBOX_FOLDERS,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  dropboxSettingsOf,
  validateDropboxValues,
} from './dropboxSource'

describe('the Dropbox source values', () => {
  it('reads one folder per line without blanks and duplicates', () => {
    expect(dropboxFoldersOf({ folders: ' /Akten \n\n/Projekte\n/Akten' })).toEqual([
      '/Akten',
      '/Projekte',
    ])
  })

  it('sends the folders, the team space only when on and the stored rhythm as it is', () => {
    expect(dropboxSettingsFrom({ ...EMPTY_DROPBOX_VALUES, folders: '/Akten' })).toEqual({
      folders: ['/Akten'],
    })
    expect(
      dropboxSettingsFrom({ folders: '/Akten', teamSpace: true, fullSyncIntervalDays: 3 }),
    ).toEqual({ folders: ['/Akten'], teamSpace: true, fullSyncIntervalDays: 3 })
  })

  it('refuses no folder, too many folders and a comma', () => {
    expect(validateDropboxValues({ ...EMPTY_DROPBOX_VALUES, folders: ' ' })).toContain(
      'mindestens einen Ordner',
    )
    const many = Array.from({ length: MAX_DROPBOX_FOLDERS + 1 }, (_, i) => `/O${i}`).join('\n')
    expect(validateDropboxValues({ ...EMPTY_DROPBOX_VALUES, folders: many })).toContain(
      `Höchstens ${MAX_DROPBOX_FOLDERS}`,
    )
    expect(validateDropboxValues({ ...EMPTY_DROPBOX_VALUES, folders: '/a,b' })).toContain('Komma')
    expect(validateDropboxValues(EMPTY_DROPBOX_VALUES)).toBeNull()
  })

  it('reads the settings of a Dropbox library only', () => {
    expect(
      dropboxSettingsOf({ sourceType: 'DROPBOX', sourceSettings: { folders: ['/Akten'] } }),
    ).toEqual({ folders: ['/Akten'] })
    expect(
      dropboxSettingsOf({ sourceType: 'NEXTCLOUD', sourceSettings: { folders: ['/Akten'] } }),
    ).toEqual({})
  })
})
