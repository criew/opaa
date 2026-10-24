import {
  EMPTY_SMB_VALUES,
  sameSmbShare,
  smbCredentialsOf,
  smbFoldersOf,
  smbSettingsOf,
  smbIssue,
  type SmbSourceValues,
} from '../../utils/smbSource'
import FolderShareForm, { type FolderShareShape } from '../sourceForms/FolderShareForm'
import { collectIssues, unifyMissing } from '../sourceKit/collectIssues'
import { folderSummary } from '../sourceKit/summaries'
import { SmbStoredView } from './SmbSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import { folderItem } from '../sourceKit/selectionSources'
import StoredSelection from '../sourceKit/StoredSelection'
import type { SourceFormContext, SourceRegistration } from './types'

const smbShape: FolderShareShape<SmbSourceValues> = {
  type: 'SMB',
  rootLabel: 'Freigabe',
  userKey: 'account',
  secretKey: 'password',
  addressPlaceholder: 'smb://dateiserver/Freigabe',
  addressHint: 'Server und Freigabe, auch als \\\\server\\freigabe.',
  userPlaceholder: 'DOMÄNE\\dienstkonto',
  secretHint: 'Kennwort des Dienstkontos',
  credentialsOf: smbCredentialsOf,
  keepsCredentials: (original, url) => sameSmbShare(original, url),
}

const smbIssues = (values: SmbSourceValues, context: SourceFormContext) =>
  unifyMissing(
    collectIssues(
      values,
      (v) =>
        smbIssue(
          v,
          !connectionFields(context).asksSecret ||
            (context.credentialsStored && sameSmbShare(context.originalSourceUrl, v.sourceUrl)),
        ),
      { sourceUrl: 'smb://stand.in/freigabe', account: 'stand-in', password: 'stand-in' },
      (field) => (field === 'folders' ? 'selection' : field),
    ),
    values,
    { sourceUrl: 'die Adresse', account: 'die Kennung', password: 'das Kennwort' },
  )

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
    issues: smbIssues,
    validate: (values: SmbSourceValues, context) => smbIssues(values, context)[0] ?? null,
    summary: (values: SmbSourceValues) => [
      { label: 'Adresse', value: values.sourceUrl.trim() },
      folderSummary(values.folders),
    ],
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
    Form: (props) => <FolderShareForm {...props} shape={smbShape} />,
    StoredView: SmbStoredView,
    Scope: ({ library }) => (
      <StoredSelection
        label="Ordner"
        items={(smbSettingsOf(library).folders ?? []).filter((f) => f !== '/').map(folderItem)}
        emptyText="Alle Ordner (/)"
      />
    ),
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
