import {
  EMPTY_SMB_VALUES,
  sameSmbShare,
  smbCredentialsOf,
  smbFoldersOf,
  smbSettingsOf,
  smbIssue,
  type SmbSourceValues,
} from '../../../utils/smbSource'
import SmbSourceForm from '../SmbSourceForm'
import { SmbScope, SmbStoredView } from './SmbSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '../../admin/connections/sourceTypeDirectory'
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
    Form: ({ values, onChange, context }) => (
      <SmbSourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-smb`}
        libraryId={context.libraryId}
        credentialsStored={context.credentialsStored}
        originalSourceUrl={context.originalSourceUrl}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
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
