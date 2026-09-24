import { describe, expect, it, vi, beforeEach } from 'vitest'
import { assetKey, useGrantStore } from './grantStore'
import { resetAllStores } from './resettableStores'
import { getAssetGrants } from '../services/api'
import type { AssetGrantResponse } from '../types/api'

const mockUpsertAssetGrant = vi.fn()
const mockRevokeAssetGrant = vi.fn()

vi.mock('../services/api', () => ({
  getAssetGrants: vi.fn(async () => []),
  upsertAssetGrant: (...args: unknown[]) => mockUpsertAssetGrant(...args),
  revokeAssetGrant: (...args: unknown[]) => mockRevokeAssetGrant(...args),
}))

const LIBRARY = assetKey('KNOWLEDGE_LIBRARY', 'library-1')

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
    useGrantStore.setState({ grantsByAsset: {}, isLoading: false, error: null })
    mockUpsertAssetGrant.mockReset()
    mockRevokeAssetGrant.mockReset()
  })

  it('loads grants for an asset under its type and id', async () => {
    vi.mocked(getAssetGrants).mockResolvedValueOnce([grant()])

    await useGrantStore.getState().loadGrants('KNOWLEDGE_LIBRARY', 'library-1')

    expect(getAssetGrants).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', 'library-1')
    expect(useGrantStore.getState().grantsByAsset[LIBRARY]).toHaveLength(1)
  })

  // #575: found while systematically checking the resettableStores registry for further
  // unguarded async set() paths beyond the ones the issue named explicitly.
  it('a loadGrants response arriving after a session reset does not resurrect grants', async () => {
    vi.mocked(getAssetGrants).mockImplementationOnce(async () => {
      resetAllStores()
      return [grant()]
    })

    await useGrantStore.getState().loadGrants('KNOWLEDGE_LIBRARY', 'library-1')

    expect(useGrantStore.getState().grantsByAsset[LIBRARY]).toBeUndefined()
    expect(useGrantStore.getState().isLoading).toBe(false)
  })

  it('an upsertExistingGrant response arriving after a session reset does not resurrect grants', async () => {
    mockUpsertAssetGrant.mockImplementationOnce(async () => {
      resetAllStores()
      return grant()
    })

    await useGrantStore.getState().upsertExistingGrant('KNOWLEDGE_LIBRARY', 'library-1', {
      subjectType: 'USER',
      subjectId: 'user-1',
      role: 'VIEWER',
    })

    expect(useGrantStore.getState().grantsByAsset[LIBRARY]).toBeUndefined()
  })
})
