import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  filesystemIssue,
  filesystemPatternsOf,
  type FilesystemSourceValues,
} from '../../../utils/filesystemSource'
import { genericSourceIssue, type GenericSourceValues } from '../../../utils/librarySourceConfig'
import type { SourceSetupSpec } from '../setupTypes'
import { addressIssue, counted, hostOf, issueList, joinSummary } from './common'
import { PathConnectionFields, PathScopeFields, UrlConnectionFields } from './GenericSetupFields'

export const filesystemSetup: SourceSetupSpec<FilesystemSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values) =>
        values.sourcePath.trim() ? joinSummary(values.sourcePath.trim()) : null,
      Fields: PathConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) => {
        const patterns = filesystemPatternsOf(values.excludePatterns)
        return patterns.length === 0
          ? 'Alle Dateien'
          : {
              lead: `Alle Dateien außer ${counted(patterns.length, ['Muster', 'Mustern'])}`,
              items: patterns,
            }
      },
      Fields: PathScopeFields,
    },
  ],
  issues: (values) => {
    const { at, list } = issueList()
    const path = genericSourceIssue('path', values)
    if (path?.field) {
      at(
        path.field,
        values.sourcePath.trim() === ''
          ? 'Verzeichnis fehlt'
          : path.message.replace('Verzeichnispfad', 'Verzeichnis'),
      )
    }
    // the pattern rules alone: the path filled with a valid stand-in
    const patterns = filesystemIssue({ ...values, sourcePath: '/x' })
    if (patterns?.field) at(patterns.field, patterns.message)
    return list
  },
  labels: { sourcePath: 'Verzeichnis', excludePatterns: 'Ausschlussmuster' },
  rowOf: (field) => (field === 'excludePatterns' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'FILESYSTEM',
      sourcePath: values.sourcePath.trim(),
    }),
  signsIn: false,
}

export const urlSetup: SourceSetupSpec<GenericSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values) => (values.sourceUrl.trim() ? joinSummary(hostOf(values.sourceUrl)) : null),
      Fields: UrlConnectionFields,
    },
  ],
  issues: (values) => {
    const { at, list } = issueList()
    const address = addressIssue(values.sourceUrl)
    if (address) at('sourceUrl', address)
    return list
  },
  labels: { sourceUrl: 'Adresse', sourceCredentials: 'Zugangsdaten' },
  rowOf: () => 'connection',
  probe: (values, fields, context) =>
    fields.probeRequest({
      sourceType: context.sourceType,
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: values.sourceCredentials.trim() || undefined,
    }),
  signsIn: false,
}
