import {
  DROPBOX_API,
  MAX_DROPBOX_FOLDERS,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  type DropboxSourceValues,
} from '../../../utils/dropboxSource'
import type { SourceSetupSpec } from '../setupTypes'
import { checkWord, folderIssues, issueList, joinSummary } from './common'
import { DropboxConnectionFields, DropboxScopeFields } from './DropboxSetupFields'
import { folderSummary } from './folderNodes'

export const dropboxSetup: SourceSetupSpec<DropboxSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, _context, check) =>
        joinSummary(values.teamSpace ? 'Team-Bereich' : 'eigene Ablage', checkWord(check)),
      Fields: DropboxConnectionFields,
    },
    { id: 'scope', summary: (values) => folderSummary(values.folders), Fields: DropboxScopeFields },
  ],
  issues: (values) => {
    const { at, list } = issueList()
    folderIssues(at, dropboxFoldersOf(values), MAX_DROPBOX_FOLDERS)
    return list
  },
  rowOf: (field) => (field === 'folders' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest({
      sourceType: 'DROPBOX',
      sourceUrl: fields.connection?.serverUrl ?? DROPBOX_API,
      sourceSettings: dropboxSettingsFrom(values),
    }),
}
