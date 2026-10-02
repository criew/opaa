import { http, HttpResponse } from 'msw'
import type {
  AssetType,
  CatalogEntryResponse,
  CatalogEntryStatus,
  CatalogVisibility,
  LibraryListResponse,
} from '../types/api'
import { mockLibraries, mockLibraryDetails } from './libraryFixtures'
import { mockPromptLibraries, mockUnreadablePromptLibraryIds } from './promptLibraryFixtures'

/**
 * A knowledge library's own state, see CatalogEntryStatus. Upload libraries are approximated by
 * their document count - the fixtures carry no per-document states.
 */
function indexingStatus(library: LibraryListResponse): CatalogEntryStatus {
  if (library.sourceType === 'UPLOAD') {
    return library.documentCount > 0 ? 'READY' : 'NOT_YET_AVAILABLE'
  }
  switch (library.lastRunStatus) {
    case 'RUNNING':
      return 'UPDATING'
    case 'FAILED':
      return 'UPDATE_FAILED'
    case 'COMPLETED':
      return 'READY'
    default:
      return 'NOT_YET_AVAILABLE'
  }
}

function visibilityOf(allAccounts: boolean): CatalogVisibility {
  return allAccounts ? 'PUBLIC' : 'RESTRICTED'
}

function readableEntries(): CatalogEntryResponse[] {
  const knowledge: CatalogEntryResponse[] = mockLibraries.map((library) => ({
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: library.id,
    name: library.name,
    description: library.description ?? null,
    ownerType: library.ownerType,
    ownerId: mockLibraryDetails[library.id]?.ownerId ?? 'mock-user-id',
    ownerLabel: library.ownerName ?? null,
    origin: 'LOCAL',
    visibility: visibilityOf(library.reach.allAccounts),
    myRole: library.myRole,
    status: library.succession ? 'SUCCESSION_OPEN' : indexingStatus(library),
    updatedAt: library.updatedAt,
    knowledgeLibrary: {
      sourceType: library.sourceType,
      lastIndexedAt: library.lastIndexedAt,
      indexingStatus: indexingStatus(library),
    },
    itemCount: library.documentCount,
    spaceCount: 0,
    succession: library.succession ?? null,
  }))
  const prompts: CatalogEntryResponse[] = Object.values(mockPromptLibraries)
    .filter((library) => !mockUnreadablePromptLibraryIds.has(library.id))
    .map((library) => ({
      assetType: 'PROMPT_LIBRARY',
      assetId: library.id,
      name: library.name,
      description: library.description ?? null,
      ownerType: library.ownerType,
      ownerId: library.ownerId,
      ownerLabel: library.ownerName ?? null,
      origin: 'LOCAL',
      visibility: visibilityOf(library.reach.allAccounts),
      myRole: library.myRole,
      status: library.succession ? 'SUCCESSION_OPEN' : 'READY',
      updatedAt: library.updatedAt,
      itemCount: library.promptCount,
      spaceCount: 1,
      succession: library.succession ?? null,
    }))
  return [...knowledge, ...prompts]
}

/**
 * The server's catalog: only what the mock user may read, filtered, searched, sorted and paged.
 * "Aus meinen Gruppen" is approximated by group ownership - the fixtures name no memberships.
 */
export const catalogHandlers = [
  http.get('/api/v1/catalog', ({ request }) => {
    const params = new URL(request.url).searchParams
    const type = params.get('type') as AssetType | null
    const q = (params.get('q') ?? '').trim().toLowerCase()
    const visibility = params.get('visibility')
    const fromMyGroups = params.get('fromMyGroups') === 'true'
    const sort = params.get('sort') ?? 'name'
    const page = Number(params.get('page') ?? '0')
    const size = Number(params.get('size') ?? '50')
    if (page < 0 || size < 1 || size > 200) {
      return HttpResponse.json({ error: 'page oder size außerhalb der Grenzen' }, { status: 400 })
    }
    if (sort !== 'name' && sort !== 'updatedAt') {
      return HttpResponse.json({ error: 'Unbekannte Sortierung' }, { status: 400 })
    }
    if (visibility && visibility !== 'PUBLIC' && visibility !== 'RESTRICTED') {
      return HttpResponse.json({ error: 'Unbekannte Sichtbarkeit' }, { status: 400 })
    }
    const all = readableEntries()
      .filter((entry) => !type || entry.assetType === type)
      .filter((entry) => !visibility || entry.visibility === visibility)
      .filter((entry) => !fromMyGroups || entry.ownerType === 'GROUP')
      .filter(
        (entry) =>
          !q ||
          entry.name.toLowerCase().includes(q) ||
          (entry.description ?? '').toLowerCase().includes(q),
      )
      .sort((a, b) =>
        sort === 'updatedAt'
          ? b.updatedAt.localeCompare(a.updatedAt) || a.assetId.localeCompare(b.assetId)
          : a.name.localeCompare(b.name),
      )
    return HttpResponse.json({
      entries: all.slice(page * size, page * size + size),
      page,
      size,
      totalElements: all.length,
      totalPages: Math.ceil(all.length / size),
    })
  }),
]
