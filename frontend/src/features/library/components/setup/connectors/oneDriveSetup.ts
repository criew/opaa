import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  MAX_ONEDRIVE_FOLDERS,
  ONEDRIVE_API,
  oneDriveFolderLabel,
  oneDriveIdsValid,
  oneDriveLabel,
  oneDriveSettingsOf,
  type OneDriveSourceValues,
} from '../../../utils/oneDriveSource'
import type { SourceSetupSpec } from '../setupTypes'
import { displayPath } from '../paths'
import { issueList, listSummary } from './common'
import { OneDriveConnectionFields, OneDriveScopeFields } from './OneDriveSetupFields'

export const oneDriveSetup: SourceSetupSpec<OneDriveSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values) => (values.driveId ? oneDriveLabel(values) : null),
      Fields: OneDriveConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) =>
        listSummary(
          values.allFolders,
          ['Ordner', 'Ordner'],
          values.folders.map((folder) => displayPath(oneDriveFolderLabel(folder))),
        ),
      Fields: OneDriveScopeFields,
    },
  ],
  issues: (values) => {
    const { at, list } = issueList()
    at('drive', 'OneDrive fehlt', values.driveId === '')
    if (!values.allFolders) {
      at('folders', 'Ordner fehlen', values.folders.length === 0)
      at(
        'folders',
        `Höchstens ${MAX_ONEDRIVE_FOLDERS} Ordner`,
        values.folders.length > MAX_ONEDRIVE_FOLDERS,
      )
    }
    const driveValid = values.driveId === '' || oneDriveIdsValid({ ...values, allFolders: true })
    at('drive', 'Das OneDrive hat keine gültige Kennung; bitte neu wählen.', !driveValid)
    at(
      'folders',
      'Ein Ordner hat keine gültige Kennung; bitte neu aus der Auflistung wählen.',
      driveValid && values.driveId !== '' && !oneDriveIdsValid(values),
    )
    return list
  },
  labels: { drive: 'OneDrive', folders: 'Ordner' },
  quietConnection: true,
  fixedAddress: true,
  rowOf: (field) => (field === 'folders' ? 'scope' : 'connection'),
  probe: (values, fields) => {
    const settings = oneDriveSettingsOf(values)
    return fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'ONEDRIVE',
      sourceUrl: ONEDRIVE_API,
      ...(settings ? { sourceSettings: settings } : {}),
    })
  },
}
