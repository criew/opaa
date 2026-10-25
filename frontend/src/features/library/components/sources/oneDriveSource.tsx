import {
  EMPTY_ONEDRIVE_VALUES,
  oneDriveChosenFolders,
  oneDriveFolderLabel,
  oneDriveLabel,
  oneDrivePayloadOf,
  oneDriveSettingsFromLibrary,
  oneDriveValuesOf,
  type OneDriveSourceValues,
} from '../../utils/oneDriveSource'
import { oneDriveSetup } from '../setup/connectors/oneDriveSetup'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

/** The last segment of a folder's shown path. */
function lastSegment(path: string) {
  const segments = path
    .split('/')
    .map((segment) => segment.trim())
    .filter(Boolean)
  return segments[segments.length - 1] ?? ''
}

/**
 * The OneDrive for Business of a person, only ever as her private library (ADR-0058): the profile
 * admits persons alone, so the wizard offers no shared way and no own address.
 */
export const oneDriveSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.ONEDRIVE,
  containerLabel: 'OneDrive',
  configuration: {
    empty: EMPTY_ONEDRIVE_VALUES,
    draftFields: [
      'driveId',
      'driveName',
      'driveFoundWith',
      'allFolders',
      'folders',
      'fullSyncIntervalDays',
    ],
    fromLibrary: (library): OneDriveSourceValues =>
      oneDriveValuesOf(oneDriveSettingsFromLibrary(library)),
    // the OneDrive is found, not entered: only a choice of folders is something to lose
    isDirty: (values: OneDriveSourceValues) => !values.allFolders || values.folders.length > 0,
    toPayload: oneDrivePayloadOf,
    nameFromSource: (values: OneDriveSourceValues) => {
      if (values.driveId === '') return ''
      const folders = oneDriveChosenFolders(values)
      if (values.allFolders) return oneDriveLabel(values)
      return folders.length === 1 ? lastSegment(oneDriveFolderLabel(folders[0])) : ''
    },
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich des OneDrive; danach liest jeder Lauf nur das Änderungsprotokoll von Microsoft Graph.',
    runsHint: '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ das OneDrive neu.',
    setup: oneDriveSetup,
  },
}
