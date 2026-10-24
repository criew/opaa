import {
  EMPTY_SMB_VALUES,
  sameSmbShare,
  smbCredentialsOf,
  smbFoldersOf,
  smbSettingsOf,
  smbIssue,
  type SmbSourceValues,
} from '../../utils/smbSource'
import ServerFolderSetup from '../setup/ServerFolderSetup'
import { accountIssues, folderListIssue } from '../setup/validation'
import { collectIssues } from '../setup/setupContext'
import { folderSummary } from '../setup/summaries'
import { MAX_SMB_FOLDERS } from '../../utils/smbSource'
import type { SourceFormContext } from './types'
import { SmbScope, SmbStoredView } from './SmbSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

function credentialsKept(values: SmbSourceValues, context: SourceFormContext) {
  return (
    !connectionFields(context).asksSecret ||
    (context.credentialsStored && sameSmbShare(context.originalSourceUrl, values.sourceUrl))
  )
}

/** The address issue alone: smbIssue reports the address first, the rest masked as valid. */
function smbAddressIssue(values: SmbSourceValues) {
  const issue = smbIssue({ ...values, account: 'a', password: 'b', folders: '/' }, true)
  return issue?.field === 'sourceUrl' ? issue : null
}

function smbPayload(values: SmbSourceValues) {
  const folders = smbFoldersOf(values)
  return {
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: smbCredentialsOf(values),
    sourceSettings: { folders: folders.length === 0 ? ['/'] : folders },
  }
}

export const smbSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.SMB,
  containerLabel: 'Ordner',
  configuration: {
    empty: EMPTY_SMB_VALUES,
    draftFields: ['sourceUrl', 'account', 'folders'],
    // the stored credentials stand until new ones are typed; they are never returned
    fromLibrary: (library): SmbSourceValues => ({
      ...EMPTY_SMB_VALUES,
      sourceUrl: library.sourceUrl ?? '',
      folders: (smbSettingsOf(library).folders ?? ['/']).join('\n'),
    }),
    isDirty: (values: SmbSourceValues) =>
      values.sourceUrl !== '' ||
      values.account !== '' ||
      values.password !== '' ||
      values.folders.trim() !== '/',
    validate: (values: SmbSourceValues, context) =>
      smbIssue(
        values,
        !connectionFields(context).asksSecret ||
          (context.credentialsStored && sameSmbShare(context.originalSourceUrl, values.sourceUrl)),
      ),
    toPayload: (values: SmbSourceValues) => {
      const folders = smbFoldersOf(values)
      return {
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: smbCredentialsOf(values),
        sourceSettings: { folders: folders.length === 0 ? ['/'] : folders },
      }
    },
    nameFromSource: (values: SmbSourceValues) => {
      const folders = smbFoldersOf(values)
      if (folders.length === 1 && folders[0] !== '/') {
        const segments = folders[0].split('/').filter(Boolean)
        return segments[segments.length - 1] ?? ''
      }
      const share = /^(?:smb:\/\/|\\\\)[^/\\]+[/\\]([^/\\]+)/i.exec(values.sourceUrl.trim())?.[1]
      if (!share) return ''
      try {
        return decodeURIComponent(share)
      } catch {
        return share
      }
    },
    validateAll: (values: SmbSourceValues, context) => [
      ...collectIssues(smbAddressIssue(values)),
      ...(connectionFields(context).asksSecret
        ? accountIssues(
            { field: 'account', value: values.account },
            { field: 'password', value: values.password },
            credentialsKept(values, context),
          )
        : []),
      ...collectIssues(folderListIssue(values.folders, MAX_SMB_FOLDERS)),
    ],
    checkStages: ['address', 'signIn', 'access'],
    summary: (values: SmbSourceValues, context) => ({
      connection: [{ label: 'Adresse der Freigabe', value: values.sourceUrl.trim(), mono: true }],
      credentials: connectionFields(context).asksSecret
        ? [{ label: 'Kennung und Kennwort', value: values.account ? `${values.account} · Kennwort gesetzt` : 'gesetzt' }]
        : undefined,
      scope: folderSummary(values.folders),
    }),
    Form: ({ values, onChange, context }) => (
      <ServerFolderSetup<SmbSourceValues>
        values={values}
        onChange={onChange}
        context={context}
        addressLabel="Adresse der Freigabe"
        addressPlaceholder="smb://dateiserver/Ablage"
        addressHint={'Server und Freigabe, auch als \\\\server\\freigabe.'}
        userField="account"
        secretField="password"
        credentialsOf={smbCredentialsOf}
        credentialsKept={credentialsKept(values, context)}
        payloadOf={smbPayload}
      />
    ),
    StoredView: SmbStoredView,
    Scope: SmbScope,
    scopeHero: {
      summary: (library) => {
        const count = smbSettingsOf(library).folders?.length ?? 1
        return count === 1 ? '1 Ordner' : `${count} Ordner`
      },
      unlistedWarning: (keys) =>
        (keys.length === 1
          ? `Der letzte Abgleich konnte den Ordner „${keys[0]}“ nicht vollständig auflisten; sein Bestand ist möglicherweise veraltet.`
          : `Der letzte Abgleich konnte die Ordner ${keys.map((key) => `„${key}“`).join(', ')} nicht vollständig auflisten; ihr Bestand ist möglicherweise veraltet.`) +
        ' Der Hinweis bleibt, bis ein Lauf wieder alle Ordner auflisten kann.',
      unlistedTestId: 'smb-incomplete-listing-warning',
    },
    firstRunHint:
      'Jeder Lauf liest die Ordner vollständig ein; unveränderte Dateien (gleiche Änderungszeit und Größe) werden dabei nicht neu abgerufen.',
  },
}
