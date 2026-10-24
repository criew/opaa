import {
  MAX_SMB_FOLDERS,
  sameSmbShare,
  smbCredentialsOf,
  smbFoldersOf,
  smbIssue,
  smbServerOf,
  smbShareOf,
  type SmbSourceValues,
} from '../../../utils/smbSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceSetupSpec } from '../setupTypes'
import { credentialsKept, folderIssues, issueList, pairIssues } from './common'
import { folderSummary } from './folderNodes'
import { SmbConnectionFields, SmbScopeFields } from './SmbSetupFields'

export const smbSetup: SourceSetupSpec<SmbSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values) =>
        values.sourceUrl.trim()
          ? `${smbServerOf(values.sourceUrl) ?? values.sourceUrl}/${smbShareOf(values.sourceUrl) ?? ''}`
          : null,
      Fields: SmbConnectionFields,
    },
    { id: 'scope', summary: (values) => folderSummary(values.folders), Fields: SmbScopeFields },
  ],
  issues: (values, context) => {
    const { at, list } = issueList()
    // the address rules of the connector alone: the other fields filled with valid stand-ins
    const address = smbIssue({ ...values, account: 'a', password: 'p', folders: '/' }, true)
    if (address?.field === 'sourceUrl') {
      at('sourceUrl', values.sourceUrl.trim() === '' ? 'Adresse fehlt' : address.message)
    }
    if (connectionFields(context).asksSecret) {
      pairIssues(
        at,
        ['account', 'password'],
        values.account.trim(),
        values.password,
        credentialsKept(context, values.sourceUrl, sameSmbShare),
        'Benutzername',
      )
    }
    folderIssues(at, smbFoldersOf(values), MAX_SMB_FOLDERS)
    return list
  },
  labels: {
    sourceUrl: 'Adresse',
    account: 'Benutzername',
    password: 'Passwort',
    folders: 'Ordner',
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
