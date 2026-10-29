import {
  DROPBOX_API,
  MAX_DROPBOX_FOLDERS,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  dropboxTeamFolderLabel,
  isDropboxTeamApp,
  type DropboxSourceValues,
} from '../../../utils/dropboxSource'
import type { SourceSetupSpec } from '../setupTypes'
import { folderIssues, issueList, joinSummary, listSummary } from './common'
import { DropboxConnectionFields, DropboxScopeFields } from './DropboxSetupFields'
import { folderSummary } from './folderNodes'

export const dropboxSetup: SourceSetupSpec<DropboxSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, context) =>
        isDropboxTeamApp(context.connection)
          ? 'Team-App'
          : joinSummary(values.teamSpace ? 'Teambereich' : 'eigene Ablage'),
      Fields: DropboxConnectionFields,
    },
    {
      id: 'scope',
      summary: (values, context) =>
        isDropboxTeamApp(context.connection)
          ? listSummary(
              false,
              ['Team-Ordner', 'Team-Ordner'],
              values.teamFolders.map(dropboxTeamFolderLabel),
            )
          : folderSummary(values.folders),
      Fields: DropboxScopeFields,
    },
  ],
  issues: (values, context) => {
    const { at, list } = issueList()
    if (isDropboxTeamApp(context.connection)) {
      at('teamFolders', 'Team-Ordner fehlen', values.teamFolders.length === 0)
      at(
        'teamFolders',
        `Höchstens ${MAX_DROPBOX_FOLDERS} Team-Ordner`,
        values.teamFolders.length > MAX_DROPBOX_FOLDERS,
      )
      return list
    }
    folderIssues(at, dropboxFoldersOf(values), MAX_DROPBOX_FOLDERS)
    at(
      'teamFolders',
      'Team-Ordner liest nur ein Zugang mit Team-App',
      values.teamFolders.length > 0,
    )
    return list
  },
  labels: { folders: 'Ordner', teamFolders: 'Team-Ordner' },
  quietConnection: true,
  fixedAddress: true,
  rowOf: (field) => (field === 'folders' || field === 'teamFolders' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest({
      sourceType: 'DROPBOX',
      sourceUrl: fields.connection?.serverUrl ?? DROPBOX_API,
      sourceSettings: dropboxSettingsFrom(values),
    }),
}
