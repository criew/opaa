import { http, HttpResponse } from 'msw'
import type { AssetType, CatalogEntryResponse } from '../types/api'
import { mockLibraries } from './fixtures'
import { mockPromptLibraries, mockUnreadablePromptLibraryIds } from './promptLibraryFixtures'

/** Listed for the organization, but without a right of the mock user - findable, not usable. */
export const mockListedWithoutAccess: CatalogEntryResponse[] = [
  {
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: 'library-listed-foreign',
    name: 'Satzungen der Kämmerei',
    description: 'Gebühren- und Beitragssatzungen, gepflegt von der Kämmerei',
    ownerType: 'GROUP',
    ownerLabel: 'Kämmerei',
    origin: 'LOCAL',
    accessible: false,
    listed: true,
    succession: null,
  },
  {
    assetType: 'PROMPT_LIBRARY',
    assetId: 'prompt-library-listed-foreign',
    name: 'Bescheidbausteine Ordnungsamt',
    description: 'Anhörung und Bescheid nach Hausstandard des Ordnungsamts',
    ownerType: 'USER',
    ownerLabel: null,
    origin: 'LOCAL',
    accessible: false,
    listed: true,
    succession: { addressee: 'SYSTEM_ADMINISTRATION', addresseeLabel: 'die Systemverwaltung' },
  },
]

function readableEntries(): CatalogEntryResponse[] {
  const knowledge: CatalogEntryResponse[] = mockLibraries.map((library) => ({
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: library.id,
    name: library.name,
    description: library.description ?? null,
    ownerType: library.ownerType,
    ownerLabel: library.ownerName ?? null,
    origin: 'LOCAL',
    accessible: true,
    listed: library.listed,
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
      ownerLabel: library.ownerName ?? null,
      origin: 'LOCAL',
      accessible: true,
      listed: library.listed,
      succession: library.succession ?? null,
    }))
  return [...knowledge, ...prompts]
}

/** The server's catalog: readable united with listed, filtered, searched and paged by name. */
export const catalogHandlers = [
  http.get('/api/v1/catalog', ({ request }) => {
    const params = new URL(request.url).searchParams
    const type = params.get('type') as AssetType | null
    const q = (params.get('q') ?? '').trim().toLowerCase()
    const page = Number(params.get('page') ?? '0')
    const size = Number(params.get('size') ?? '50')
    if (page < 0 || size < 1 || size > 200) {
      return HttpResponse.json({ error: 'page oder size außerhalb der Grenzen' }, { status: 400 })
    }
    const all = [...readableEntries(), ...mockListedWithoutAccess]
      .filter((entry) => !type || entry.assetType === type)
      .filter(
        (entry) =>
          !q ||
          entry.name.toLowerCase().includes(q) ||
          (entry.description ?? '').toLowerCase().includes(q),
      )
      .sort((a, b) => a.name.localeCompare(b.name))
    return HttpResponse.json({
      entries: all.slice(page * size, page * size + size),
      page,
      size,
      totalElements: all.length,
      totalPages: Math.ceil(all.length / size),
    })
  }),
]
