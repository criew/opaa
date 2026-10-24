import {
  MAX_SMB_FOLDERS,
  smbCredentialsOf,
  smbFoldersOf,
  smbIssue,
  smbServerOf,
  smbShareOf,
  type SmbSourceValues,
} from '../../../utils/smbSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceSetupSpec } from '../setupTypes'
import {
  checkWord,
  credentialsKept,
  folderIssues,
  issueList,
  joinSummary,
  pairIssues,
} from './common'
import { folderSummary } from './folderNodes'
import { SmbConnectionFields, SmbScopeFields } from './SmbSetupFields'

export const smbSetup: SourceSetupSpec<SmbSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, _context, check) =>
        values.sourceUrl.trim()
          ? joinSummary(
              `${smbServerOf(values.sourceUrl) ?? values.sourceUrl}/${smbShareOf(values.sourceUrl) ?? ''}`,
              checkWord(check),
            )
          : null,
      Fields: SmbConnectionFields,
    },
    { id: 'scope', summary: (values) => folderSummary(values.folders), Fields: SmbScopeFields },
  ],
  issues: (values, context) => {
    const { at, list } = issueList()
    // the address rules of the connector alone: the other fields filled with valid stand-ins
    const address = smbIssue({ ...values, account: 'a', password: 'p', folders: '/' }, true)
    if (address?.field === 'sourceUrl') at('sourceUrl', address.message)
    if (connectionFields(context).asksSecret) {
      pairIssues(
        at,
        ['account', 'password'],
        values.account.trim(),
        values.password,
        credentialsKept(context, values.sourceUrl),
      )
    }
    folderIssues(at, smbFoldersOf(values), MAX_SMB_FOLDERS)
    return list
  },
  rowOf: (field) => (field === 'folders' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest({
      sourceType: 'SMB',
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: smbCredentialsOf(values),
      sourceSettings: { folders: smbFoldersOf(values) },
    }),
}
