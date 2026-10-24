import {
  EMPTY_SMB_VALUES,
  sameSmbShare,
  smbCredentialsOf,
  smbFoldersOf,
  smbSettingsOf,
  smbIssue,
  type SmbSourceValues,
} from '../../utils/smbSource'
import AccountFolderForm from '../setup/forms/AccountFolderForm'
import { accountIssues, collectIssues } from '../setup/collectIssues'
import { pickedOfFolders } from '../setup/pathSelection'
import { MAX_SMB_FOLDERS } from '../../utils/smbSource'
import { SmbScope, SmbStoredView } from './SmbSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

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
    validateAll: (values: SmbSourceValues, context) => {
      const asks = connectionFields(context).asksSecret
      const first = (probe: SmbSourceValues) =>
        smbSource.configuration?.validate(probe, context) ?? null
      const own = asks
        ? accountIssues(
            values.account,
            values.password,
            Boolean(context.credentialsStored) &&
              sameSmbShare(context.originalSourceUrl, values.sourceUrl),
            {
              account: 'account',
              secret: 'password',
              secretLabel: 'Passwort',
            },
          )
        : []
      const rest = collectIssues(
        { ...values, account: '', password: '' },
        (probe) => first({ ...probe, account: 'konto', password: 'x' }),
        {
          sourceUrl: 'smb://server/freigabe',
        },
      )
      return [
        ...rest.filter((issue) => issue.field === 'sourceUrl'),
        ...own,
        ...rest.filter((issue) => issue.field !== 'sourceUrl'),
      ]
    },
    summary: (values: SmbSourceValues, context) => ({
      connection: [
        { label: 'Adresse', value: values.sourceUrl.trim(), mono: true },
        {
          label: 'Konto',
          value: values.account.trim() || (context.credentialsStored ? 'gespeichert' : ''),
        },
      ],
      selection: {
        rootLabel: 'Freigabe',
        noun: { one: 'Ordner', many: 'Ordner' },
        items: pickedOfFolders(smbFoldersOf(values)),
        empty: 'Die ganze Freigabe',
      },
    }),
    Form: ({ values, onChange, context }) => (
      <AccountFolderForm<SmbSourceValues>
        sourceType="SMB"
        values={values}
        onChange={onChange}
        context={context}
        accountKey="account"
        secretKey="password"
        secretLabel="Passwort"
        addressPlaceholder="smb://dateiserver/Ablage"
        addressHint="Server und Freigabe, auch als \\server\freigabe."
        rootLabel="Freigabe"
        credentialsOf={smbCredentialsOf}
        foldersOf={smbFoldersOf}
        keepsSecret={(current) => sameSmbShare(context.originalSourceUrl, current.sourceUrl)}
        max={MAX_SMB_FOLDERS}
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
