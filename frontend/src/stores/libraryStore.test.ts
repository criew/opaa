import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useLibraryStore } from './libraryStore'
import { resetAllStores } from './resettableStores'
import { getLibraries } from '../services/api'
import type { LibraryListResponse } from '../types/api'

/** Resolves once resolve() is called - lets a test hold loadLibraries()'s request open until it
 * explicitly wants the response to arrive, so it can trigger resetAllStores() while the request
 * is still in flight (#575). */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

function library(overrides: Partial<LibraryListResponse> = {}): LibraryListResponse {
  return {
    id: 'library-a',
    name: 'A',
    description: null,
    ownerType: 'USER',
    visibility: 'PRIVATE',
    listed: false,
    myRole: 'OWNER',
    sourceType: 'UPLOAD',
    documentCount: 0,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
    ...overrides,
  }
}

vi.mock('../services/api', () => ({
  getLibraries: vi.fn(async () => [
    { id: 'library-b', name: 'B' },
    { id: 'library-a', name: 'A' },
  ]),
  getLibrary: vi.fn(),
  createLibrary: vi.fn(),
  updateLibrary: vi.fn(),
  deleteLibrary: vi.fn(),
}))

describe('libraryStore', () => {
  beforeEach(() => {
    vi.mocked(getLibraries).mockImplementation(async () => [
      library({ id: 'library-b', name: 'B' }),
      library({ id: 'library-a', name: 'A' }),
    ])
    useLibraryStore.setState({ libraries: [], libraryDetails: {}, isLoading: false, error: null })
  })

  it('sorts libraries alphabetically', async () => {
    await useLibraryStore.getState().loadLibraries()
    const names = useLibraryStore.getState().libraries.map((l) => l.name)
    expect(names).toEqual(['A', 'B'])
  })

  // #575: loadLibraries is one of the explicitly named unguarded write paths (Issue #575) - a
  // response arriving after resetAllStores() must not resurrect the previous user's libraries
  // into the now-emptied store.
  it('a loadLibraries response arriving after a session reset does not resurrect libraries', async () => {
    const gate = deferred<LibraryListResponse[]>()
    vi.mocked(getLibraries).mockReturnValueOnce(gate.promise)

    const loadPromise = useLibraryStore.getState().loadLibraries()
    resetAllStores()
    gate.resolve([library()])
    await loadPromise

    const state = useLibraryStore.getState()
    expect(state.libraries).toEqual([])
    expect(state.isLoading).toBe(false)
  })
})
