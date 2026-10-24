import {
  MAX_NEXTCLOUD_FOLDERS,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  type NextcloudSourceValues,
} from '../../../utils/nextcloudSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceSetupSpec } from '../setupTypes'
import {
  addressIssue,
  checkWord,
  credentialsKept,
  folderIssues,
  hostOf,
  issueList,
  joinSummary,
  pairIssues,
} from './common'
import { folderSummary } from './folderNodes'
import { NextcloudConnectionFields, NextcloudScopeFields } from './NextcloudSetupFields'

export const nextcloudSetup: SourceSetupSpec<NextcloudSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, _context, check) =>
        values.sourceUrl.trim() ? joinSummary(hostOf(values.sourceUrl), checkWord(check)) : null,
      Fields: NextcloudConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) => folderSummary(values.folders),
      Fields: NextcloudScopeFields,
    },
  ],
  issues: (values, context) => {
    const { at, list } = issueList()
    const address = addressIssue(values.sourceUrl)
    if (address) at('sourceUrl', address)
    if (connectionFields(context).asksSecret) {
      pairIssues(
        at,
        ['username', 'appPassword'],
        values.username.trim(),
        values.appPassword,
        credentialsKept(context, values.sourceUrl),
        'Benutzername',
        'App-Passwort',
      )
    }
    folderIssues(at, nextcloudFoldersOf(values), MAX_NEXTCLOUD_FOLDERS)
    return list
  },
  rowOf: (field) => (field === 'folders' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest({
      sourceType: 'NEXTCLOUD',
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: nextcloudCredentialsOf(values),
      sourceSettings: { folders: nextcloudFoldersOf(values) },
    }),
}
