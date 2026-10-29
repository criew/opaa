import { describe, expect, it } from 'vitest'
import {
  EMPTY_DROPBOX_VALUES,
  MAX_DROPBOX_FOLDERS,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  dropboxSettingsOf,
  isDropboxTeamApp,
  validateDropboxValues,
} from './dropboxSource'
import { dropboxSetup } from '../components/setup/connectors/dropboxSetup'
import { dropboxSource } from '../components/sources/dropboxSource'
import type { SourceConnection } from '../components/sources/sourceConnection'
import type { SourceFormContext } from '../components/sources/types'

const TEAM_APP: SourceConnection = {
  profileId: 'profile-team',
  name: 'Dropbox Team',
  serverUrl: 'https://api.dropboxapi.com',
  authMethod: 'OAUTH',
  defaults: { teamApp: true },
}

function contextOn(connection?: SourceConnection): SourceFormContext {
  return {
    mode: 'create',
    sourceType: 'DROPBOX',
    idPrefix: 'dbx',
    credentialsStored: false,
    connection,
  }
}

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
      dropboxSettingsFrom({
        ...EMPTY_DROPBOX_VALUES,
        folders: '/Akten',
        teamSpace: true,
        fullSyncIntervalDays: 3,
      }),
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

describe('Dropbox team folders of a team app', () => {
  const bauamt = { id: '100', name: 'Bauamt' }

  it('knows a team app by the default of its Zugang', () => {
    expect(isDropboxTeamApp(TEAM_APP)).toBe(true)
    expect(isDropboxTeamApp({ ...TEAM_APP, defaults: {} })).toBe(false)
    expect(isDropboxTeamApp(null)).toBe(false)
  })

  it('sends the team folders instead of paths, with the stored rhythm', () => {
    expect(
      dropboxSettingsFrom({
        ...EMPTY_DROPBOX_VALUES,
        teamFolders: [bauamt, { id: '200', name: null }],
        fullSyncIntervalDays: 3,
      }),
    ).toEqual({
      teamFolders: [bauamt, { id: '200' }],
      fullSyncIntervalDays: 3,
    })
  })

  it('reads the team folders of a stored library', () => {
    expect(
      dropboxSource.configuration.fromLibrary({
        sourceType: 'DROPBOX',
        sourceSettings: { teamFolders: [bauamt], teamApp: true },
      }),
    ).toMatchObject({ teamFolders: [bauamt] })
  })

  it('asks for team folders under a team app and for none under a member app', () => {
    expect(dropboxSetup.issues(EMPTY_DROPBOX_VALUES, contextOn(TEAM_APP))).toEqual([
      { field: 'teamFolders', message: 'Team-Ordner fehlen' },
    ])
    expect(
      dropboxSetup.issues({ ...EMPTY_DROPBOX_VALUES, teamFolders: [bauamt] }, contextOn(TEAM_APP)),
    ).toEqual([])
    expect(
      dropboxSetup.issues(
        { ...EMPTY_DROPBOX_VALUES, teamFolders: [bauamt] },
        contextOn({ ...TEAM_APP, defaults: {} }),
      ),
    ).toEqual([{ field: 'teamFolders', message: 'Team-Ordner liest nur ein Zugang mit Team-App' }])
    expect(dropboxSetup.rowOf('teamFolders')).toBe('scope')
  })

  it('summarises the rows: the team app and the chosen team folders by name', () => {
    const [connection, scope] = dropboxSetup.sections
    const values = { ...EMPTY_DROPBOX_VALUES, teamFolders: [bauamt, { id: '200', name: null }] }
    expect(connection.summary(values, contextOn(TEAM_APP))).toBe('Team-App')
    expect(scope.summary(values, contextOn(TEAM_APP))).toEqual({
      lead: '2 Team-Ordner',
      items: ['Bauamt', 'Team-Ordner 200'],
    })
  })
})
