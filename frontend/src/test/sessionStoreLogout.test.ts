import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/shared/session/authStore'
import { useSpaceStore } from '@/features/space'
import { useGroupStore } from '@/features/group'
import { useLibraryStore } from '../stores/libraryStore'
import { useChatStore } from '../stores/chatStore'
import { useChatListStore } from '../stores/chatListStore'
import { useDocumentStore } from '../stores/documentStore'
import { useGrantStore } from '@/features/asset'
import { useIndexingStore } from '../stores/indexingStore'

/**
 * Regression guard for #440: data of the signed-in session is gone from the real stores after
 * a logout, so the next account in the same tab never sees it.
 */
describe('logout empties the session stores', () => {
  it('clears state on logout', async () => {
    sessionStorage.setItem('opaa.devAuth.user', 'dev-user')
    useAuthStore.setState({
      mode: 'dev',
      token: 'some-token',
      user: { id: '1', email: 'test@test.com', displayName: 'Test', systemRole: 'USER' as const },
      isAuthenticated: true,
      isLoading: false,
    })
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-1',
          name: 'Test',
          description: '',
          isDefault: true,
          archived: false,
          memberCount: 1,
          memberships: { groupCount: 0, userCount: 1 },
          userRole: 'ADMIN',
          createdAt: '',
          updatedAt: '',
        },
      ],
      selectedSpaceId: 'space-1',
    })
    // #440: groupStore, libraryStore and chatStore/chatListStore cache data scoped to the
    // signed-in user's session just like spaceStore - a lingering entry here after logout would
    // mean the next user signing in in the same tab briefly sees the previous user's data.
    useGroupStore.setState({
      groups: [
        {
          id: 'group-1',
          name: 'Test',
          description: null,
          kind: 'AD_HOC',
          externalId: null,
          origin: 'INTERNAL',
          state: 'ACTIVE',
          provider: null,
          sourcePath: null,
          parentGroupId: null,
          memberCount: 1,
          dissolved: false,
          deactivated: false,
          releasedForUse: true,
          protectedGroup: false,
          stewards: [],
          createdAt: '',
          updatedAt: '',
        },
      ],
    })
    useLibraryStore.setState({
      libraries: [
        {
          id: 'library-1',
          name: 'Test',
          description: null,
          ownerType: 'USER',
          reach: { allAccounts: false, groupCount: 0, userCount: 1 },
          myRole: 'OWNER',
          sourceType: 'UPLOAD',
          documentCount: 0,
          createdAt: '',
          updatedAt: '',
        },
      ],
    })
    useChatStore.setState({ spaceId: 'space-1', chatId: 'chat-1', title: 'Test' })
    useChatListStore.setState({ chatsBySpaceId: { 'space-1': [] } })
    // #440 review: documentStore, grantStore and indexingStore cache data scoped to the
    // signed-in user's session just like the stores above.
    useDocumentStore.setState({
      documentsByLibrary: {
        'library-1': [
          {
            id: 'document-1',
            fileName: 'test.pdf',
            contentType: 'application/pdf',
            fileSize: 1000,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 1,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: 'mock-user-id',
          },
        ],
      },
    })
    useGrantStore.setState({
      grantsByAsset: {
        'library-1': [
          {
            id: 'grant-1',
            subjectType: 'USER',
            subjectId: 'user-1',
            subjectDisplayName: 'Test',
            role: 'VIEWER',
            createdAt: '',
            updatedAt: '',
          },
        ],
      },
    })
    useIndexingStore.setState({
      runsByLibrary: {
        'library-1': {
          status: 'RUNNING',
          documentCount: 1,
          totalDocuments: 5,
          documentsSkipped: 0,
          documentsFailed: 0,
          documentsIndexedTotal: 1,
          message: null,
          timestamp: '2026-03-01T10:00:00Z',
          isPolling: true,
          sourceType: 'FILESYSTEM',
          unlistedScopeKeys: [],
          failureCategory: null,
        },
      },
    })

    await useAuthStore.getState().logout()

    const authState = useAuthStore.getState()
    expect(authState.isAuthenticated).toBe(false)
    expect(authState.token).toBeNull()
    expect(authState.user).toBeNull()
    expect(sessionStorage.getItem('opaa.devAuth.user')).toBeNull()

    const spaceState = useSpaceStore.getState()
    expect(spaceState.spaces).toEqual([])
    expect(spaceState.selectedSpaceId).toBeNull()

    expect(useGroupStore.getState().groups).toEqual([])
    expect(useLibraryStore.getState().libraries).toEqual([])
    expect(useChatStore.getState().spaceId).toBeNull()
    expect(useChatStore.getState().chatId).toBeNull()
    expect(useChatListStore.getState().chatsBySpaceId).toEqual({})
    expect(useDocumentStore.getState().documentsByLibrary).toEqual({})
    expect(useGrantStore.getState().grantsByAsset).toEqual({})
    expect(useIndexingStore.getState().runsByLibrary).toEqual({})
  })
})
