import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useGrantStore } from './grantStore'
import { resetAllStores } from './resettableStores'
import { getLibraryGrants } from '../services/api'
import type { AssetGrantResponse } from '../types/api'

const mockUpsertLibraryGrant = vi.fn()
const mockRevokeLibraryGrant = vi.fn()

vi.mock('../services/api', () => ({
  getLibraryGrants: vi.fn(async () => []),
  upsertLibraryGrant: (...args: unknown[]) => mockUpsertLibraryGrant(...args),
  revokeLibraryGrant: (...args: unknown[]) => mockRevokeLibraryGrant(...args),
}))

function grant(overrides: Partial<AssetGrantResponse> = {}): AssetGrantResponse {
  return {
    id: 'grant-1',
    subjectType: 'USER',
    subjectId: 'user-1',
    subjectDisplayName: 'Alice',
    role: 'VIEWER',
    expiresAt: null,
    grantedByUserId: 'mock-user-id',
    grantedByDisplayName: 'Admin',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
    ...overrides,
  }
}

describe('grantStore', () => {
  beforeEach(() => {
    useGrantStore.setState({ grantsByLibrary: {}, isLoading: false, error: null })
    mockUpsertLibraryGrant.mockReset()
    mockRevokeLibraryGrant.mockReset()
  })

  it('loads grants for a library', async () => {
    vi.mocked(getLibraryGrants).mockResolvedValueOnce([grant()])

    await useGrantStore.getState().loadGrants('library-1')

    expect(useGrantStore.getState().grantsByLibrary['library-1']).toHaveLength(1)
  })

  // #575: found while systematically checking the resettableStores registry for further
  // unguarded async set() paths beyond the ones the issue named explicitly.
  it('a loadGrants response arriving after a session reset does not resurrect grants', async () => {
    vi.mocked(getLibraryGrants).mockImplementationOnce(async () => {
      resetAllStores()
      return [grant()]
    })

    await useGrantStore.getState().loadGrants('library-1')

    expect(useGrantStore.getState().grantsByLibrary['library-1']).toBeUndefined()
    expect(useGrantStore.getState().isLoading).toBe(false)
  })

  it('an upsertExistingGrant response arriving after a session reset does not resurrect grants', async () => {
    mockUpsertLibraryGrant.mockImplementationOnce(async () => {
      resetAllStores()
      return grant()
    })

    await useGrantStore.getState().upsertExistingGrant('library-1', {
      subjectType: 'USER',
      subjectId: 'user-1',
      role: 'VIEWER',
    })

    expect(useGrantStore.getState().grantsByLibrary['library-1']).toBeUndefined()
  })
})
