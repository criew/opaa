import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  MAX_SHAREPOINT_FOLDERS,
  MAX_SHAREPOINT_LIBRARIES,
  SHAREPOINT_API,
  sharePointIdsValid,
  sharePointFolderLabel,
  sharePointLibraryLabel,
  sharePointSettingsOf,
  type SharePointSourceValues,
} from '../../../utils/sharePointSource'
import type { SourceSetupSpec } from '../setupTypes'
import { counted, issueList } from './common'
import { displayPath } from '../paths'
import { SharePointConnectionFields, SharePointScopeFields } from './SharePointSetupFields'

export const sharePointSetup: SourceSetupSpec<SharePointSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: () => null,
      Fields: SharePointConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) => {
        if (values.libraries.length === 0) return null
        const folders = values.libraries.reduce((sum, library) => sum + library.folders.length, 0)
        const lead = [
          counted(values.libraries.length, ['Dokumentbibliothek', 'Dokumentbibliotheken']),
          folders > 0 && `davon ${counted(folders, ['Ordner', 'Ordner'])}`,
        ]
          .filter(Boolean)
          .join(', ')
        return {
          lead,
          items: values.libraries.flatMap((library) =>
            library.folders.length === 0
              ? [sharePointLibraryLabel(library)]
              : library.folders.map((folder) =>
                  displayPath(
                    `${sharePointLibraryLabel(library)}/${sharePointFolderLabel(folder)}`,
                  ),
                ),
          ),
        }
      },
      Fields: SharePointScopeFields,
    },
  ],
  issues: (values) => {
    const { at, list } = issueList()
    at('libraries', 'Dokumentbibliotheken fehlen', values.libraries.length === 0)
    at(
      'libraries',
      `Höchstens ${MAX_SHAREPOINT_LIBRARIES} Dokumentbibliotheken`,
      values.libraries.length > MAX_SHAREPOINT_LIBRARIES,
    )
    at(
      'libraries',
      `Höchstens ${MAX_SHAREPOINT_FOLDERS} Ordner je Dokumentbibliothek`,
      values.libraries.some((library) => library.folders.length > MAX_SHAREPOINT_FOLDERS),
    )
    at(
      'libraries',
      'Eine Dokumentbibliothek oder ein Ordner hat keine gültige Kennung; bitte neu aus der Auflistung wählen.',
      values.libraries.some((library) => !sharePointIdsValid(library)),
    )
    return list
  },
  labels: { libraries: 'Dokumentbibliotheken und Ordner' },
  quietConnection: true,
  fixedAddress: true,
  rowOf: (field) => (field === 'libraries' || field === 'site' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'SHAREPOINT',
      sourceUrl: SHAREPOINT_API,
      sourceSettings: sharePointSettingsOf(values),
    }),
}
