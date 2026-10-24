import { browseSource } from '../../../api/libraryApi'
import {
  SHAREPOINT_API,
  sharePointFolderLabel,
  sharePointKeyOf,
  sharePointLibraryLabel,
  type SharePointLibraryChoice,
  type SharePointSourceValues,
} from '../../../utils/sharePointSource'
import { checkByListing } from '../connectionCheck'
import type { ScopeNode, StepContext, StepwiseSource } from '../types'

function browse(query: Record<string, unknown>, ctx: StepContext) {
  return browseSource('SHAREPOINT', ctx.fields.probeRequest({ sourceUrl: SHAREPOINT_API, query }))
}

/** The selection as nodes: a whole document library, or its chosen folders below it. */
function selectionOf(values: SharePointSourceValues): ScopeNode[] {
  return values.libraries.flatMap((library): ScopeNode[] => {
    const libraryId = `drive:${library.driveId}`
    const libraryName = sharePointLibraryLabel(library)
    if (library.folders.length === 0) {
      return [
        {
          id: libraryId,
          name: libraryName,
          ancestors: [],
          trail: [],
          expandable: true,
          selectable: true,
        },
      ]
    }
    return library.folders.map((folder) => ({
      id: `folder:${folder.id}`,
      name: sharePointFolderLabel(folder),
      ancestors: [libraryId],
      trail: [libraryName],
      expandable: true,
      selectable: true,
    }))
  })
}

function librariesOf(nodes: ScopeNode[]): SharePointLibraryChoice[] {
  const libraries = new Map<string, SharePointLibraryChoice>()
  for (const node of nodes) {
    const key = sharePointKeyOf(node.id)
    if (!key) continue
    if (key.kind === 'drive') {
      libraries.set(key.id, { driveId: key.id, name: node.name, folders: [] })
      continue
    }
    const at = node.ancestors.findIndex((id) => id.startsWith('drive:'))
    if (at < 0) continue
    const driveId = node.ancestors[at].slice('drive:'.length)
    const library = libraries.get(driveId) ?? { driveId, name: node.trail[at] ?? null, folders: [] }
    // the folder's path below the library is its stored name
    const below = node.trail.slice(at + 1)
    library.folders.push({ id: key.id, name: [...below, node.name].join(' / ') })
    libraries.set(driveId, library)
  }
  return [...libraries.values()]
}

export const sharePointStepwise: StepwiseSource<SharePointSourceValues> = {
  connectionHeading: 'Verbindung zu SharePoint',
  fields: () => [],
  connectionErrors: () => ({}),
  stageFields: { address: [], signIn: [] },
  check: (_values, ctx) => checkByListing(() => browse({ search: '' }, ctx), 'Sites'),
  connectionRows: () => [],
  scope: {
    heading: 'Welche Dokumentbibliotheken soll die Bibliothek lesen?',
    noun: ['Dokumentbibliothek', 'Dokumentbibliotheken'],
    hierarchical: true,
    load: async (parent, _values, ctx) => {
      const kind = parent ? sharePointKeyOf(parent.id) : null
      const query: Record<string, unknown> = !parent
        ? { search: '' }
        : kind?.kind === 'site'
          ? { site: kind.id }
          : kind?.kind === 'drive'
            ? { drive: kind.id }
            : {
                drive: parent.ancestors
                  .find((id) => id.startsWith('drive:'))
                  ?.slice('drive:'.length),
                folder: kind?.id,
              }
      const result = await browse(query, ctx)
      const ancestors = parent ? [...parent.ancestors, parent.id] : []
      const trail = parent ? [...parent.trail, parent.name] : []
      return {
        nodes: result.entries.map((entry) => {
          const site = entry.key.startsWith('site:')
          return {
            id: entry.key,
            name: entry.name || entry.key,
            ancestors,
            trail,
            expandable: true,
            selectable: !site,
            meta: site ? 'Site' : undefined,
          }
        }),
        complete: result.complete,
        message: result.message,
      }
    },
    selection: selectionOf,
    withSelection: (_values, nodes) => ({ libraries: librariesOf(nodes) }),
    max: 50,
    countForMax: (nodes) => librariesOf(nodes).length,
    errors: (values) =>
      values.libraries.length === 0
        ? { scope: 'Bitte mindestens eine Dokumentbibliothek wählen.' }
        : {},
    failedEntries: (response) => {
      const libraries = (response.details?.libraries ?? []) as Array<{
        driveId: string
        reachable: boolean
        message?: string | null
      }>
      return Object.fromEntries(
        libraries
          .filter((l) => !l.reachable)
          .map((l) => [`drive:${l.driveId}`, l.message ?? 'nicht erreichbar']),
      )
    },
  },
  scopeRows: (values) => [
    {
      label: 'Dokumentbibliotheken',
      value: `${values.libraries.length} ${values.libraries.length === 1 ? 'Dokumentbibliothek' : 'Dokumentbibliotheken'}`,
      list: values.libraries.map((library) =>
        library.folders.length === 0
          ? sharePointLibraryLabel(library)
          : `${sharePointLibraryLabel(library)}: ${library.folders.map(sharePointFolderLabel).join(', ')}`,
      ),
    },
  ],
}
