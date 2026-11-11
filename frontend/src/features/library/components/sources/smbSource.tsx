import {
  EMPTY_SMB_VALUES,
  smbCredentialsOf,
  smbFoldersOf,
  smbSettingsOf,
  type SmbSourceValues,
} from '../../utils/smbSource'
import { smbSetup } from '../setup/connectors/smbSetup'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from '../sourceCore/types'

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
    toPayload: (values: SmbSourceValues) => {
      const folders = smbFoldersOf(values)
      return {
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: smbCredentialsOf(values),
        sourceSettings: { folders: folders.length === 0 ? ['/'] : folders },
      }
    },
    scopeNoteId: 'smb-sharing-consequence',
    firstRunHint:
      'Jeder Lauf liest die Ordner vollständig ein; unveränderte Dateien (gleiche Änderungszeit und Größe) werden dabei nicht neu abgerufen.',
    setup: smbSetup,
  },
}
