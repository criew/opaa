import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  SHAREPOINT_API,
  sharePointFolderLabel,
  sharePointLibraryLabel,
  sharePointSettingsOf,
  type SharePointSourceValues,
} from '../../../utils/sharePointSource'
import type { SourceSetupSpec } from '../setupTypes'
import { checkWord, issueList, namesSummary } from './common'
import { SharePointConnectionFields, SharePointScopeFields } from './SharePointSetupFields'

const MAX_LIBRARIES = 50
const MAX_FOLDERS = 50

export const sharePointSetup: SourceSetupSpec<SharePointSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (_values, _context, check) => checkWord(check),
      Fields: SharePointConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) =>
        namesSummary(
          values.libraries.flatMap((library) =>
            library.folders.length === 0
              ? [sharePointLibraryLabel(library)]
              : library.folders.map(
                  (folder) =>
                    `${sharePointLibraryLabel(library)} › ${sharePointFolderLabel(folder)}`,
                ),
          ),
        ),
      Fields: SharePointScopeFields,
    },
  ],
  issues: (values) => {
    const { at, list } = issueList()
    at('libraries', 'Mindestens eine Dokumentbibliothek wählen', values.libraries.length === 0)
    at(
      'libraries',
      `Höchstens ${MAX_LIBRARIES} Dokumentbibliotheken`,
      values.libraries.length > MAX_LIBRARIES,
    )
    at(
      'libraries',
      `Höchstens ${MAX_FOLDERS} Ordner je Dokumentbibliothek`,
      values.libraries.some((library) => library.folders.length > MAX_FOLDERS),
    )
    return list
  },
  rowOf: (field) => (field === 'libraries' || field === 'site' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'SHAREPOINT',
      sourceUrl: SHAREPOINT_API,
      sourceSettings: sharePointSettingsOf(values),
    }),
}
