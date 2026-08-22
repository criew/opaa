import { describe, it, expect, beforeEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { User } from 'oidc-client-ts'
import { server } from '../mocks/server'
import { useAuthStore } from './authStore'
import { useSpaceStore } from './spaceStore'
import { useGroupStore } from './groupStore'
import { useLibraryStore } from './libraryStore'
import { useChatStore } from './chatStore'
import { useChatListStore } from './chatListStore'
import { useDocumentStore } from './documentStore'
import { useGrantStore } from './grantStore'
import { useIndexingStore } from './indexingStore'

describe('authStore', () => {
  beforeEach(() => {
    sessionStorage.clear()
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: true,
      error: null,
      userManager: null,
    })
  })

  it('initializes with loading state', () => {
    const state = useAuthStore.getState()
    expect(state.isLoading).toBe(true)
    expect(state.isAuthenticated).toBe(false)
    expect(state.mode).toBeNull()
  })

  it('initializes as authenticated in dev mode and loads the current user', async () => {
    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.mode).toBe('dev')
    expect(state.isAuthenticated).toBe(true)
    expect(state.isLoading).toBe(false)
    expect(state.user).toEqual({
      id: 'mock-user-id',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole: 'SYSTEM_ADMIN',
    })
  })

  it('remembers the dev user from the URL for the rest of the session', async () => {
    window.history.replaceState({}, '', '/chat?devUser=dev-user')

    await useAuthStore.getState().initialize()

    expect(sessionStorage.getItem('opaa.devAuth.user')).toBe('dev-user')
    window.history.replaceState({}, '', '/')
  })

  it('stays unauthenticated and reports an error when the auth config cannot be loaded', async () => {
    server.use(http.get('/api/v1/auth/config', () => HttpResponse.error()))

    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.mode).toBeNull()
    expect(state.isLoading).toBe(false)
    expect(state.error).toBeTruthy()
  })

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
          visibility: 'PRIVATE',
          memberCount: 1,
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
          parentGroupId: null,
          memberCount: 1,
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
          visibility: 'PRIVATE',
          listed: true,
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
      grantsByLibrary: {
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
    expect(useGrantStore.getState().grantsByLibrary).toEqual({})
    expect(useIndexingStore.getState().runsByLibrary).toEqual({})
  })

  it('returns access token via getAccessToken', async () => {
    useAuthStore.setState({ token: 'test-token' })
    await expect(useAuthStore.getState().getAccessToken()).resolves.toBe('test-token')
  })

  it('stays unauthenticated in oidc mode without a stored session', async () => {
    server.use(
      http.get('/api/v1/auth/config', () =>
        HttpResponse.json({
          mode: 'oidc',
          authority: 'https://idp.example.test/realms/opaa',
          clientId: 'opaa-frontend',
        }),
      ),
    )

    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.mode).toBe('oidc')
    expect(state.isAuthenticated).toBe(false)
    expect(state.isLoading).toBe(false)
  })

  // #737: oidc-client-ts renews the access token in the background via the refresh token
  // (automaticSilentRenew), but the store used to capture the token only once, as a snapshot, at
  // login/callback time (see the two `set({ token: ... })` calls above) - a UserManager that had
  // silently renewed never fed the new token back into the store, so the next request kept
  // sending the now-expired one until the response interceptor's immediate signoutRedirect() ended
  // the whole session.
  describe('silent token renewal (#737)', () => {
    async function initializeOidcMode() {
      server.use(
        http.get('/api/v1/auth/config', () =>
          HttpResponse.json({
            mode: 'oidc',
            authority: 'https://idp.example.test/realms/opaa',
            clientId: 'opaa-frontend',
          }),
        ),
      )
      await useAuthStore.getState().initialize()
      const { userManager } = useAuthStore.getState()
      if (!userManager) throw new Error('expected a userManager in oidc mode')
      return userManager
    }

    it('updates the token when the UserManager fires UserLoaded (silent renew)', async () => {
      const userManager = await initializeOidcMode()

      const renewedUser = new User({
        access_token: 'renewed-access-token',
        token_type: 'Bearer',
        profile: {
          sub: 'user-1',
          iss: 'https://idp.example.test/realms/opaa',
          aud: 'opaa-frontend',
          exp: Math.floor(Date.now() / 1000) + 900,
          iat: Math.floor(Date.now() / 1000),
        },
        expires_at: Math.floor(Date.now() / 1000) + 900,
      })

      await userManager.events.load(renewedUser)

      await expect(useAuthStore.getState().getAccessToken()).resolves.toBe('renewed-access-token')
    })

    it('clears the token when the UserManager fires UserUnloaded', async () => {
      const userManager = await initializeOidcMode()
      useAuthStore.setState({ token: 'some-token', isAuthenticated: true })

      await userManager.events.unload()

      const state = useAuthStore.getState()
      expect(state.token).toBeNull()
      expect(state.isAuthenticated).toBe(false)
    })

    // #737 review, finding 1: a background renew that resolves after expireSession() reset the
    // store must not half-reanimate the session - the UserLoaded handler used to set
    // isAuthenticated: true unconditionally, regardless of whether a user was still known.
    it('does not reactivate the session when UserLoaded fires after expireSession', async () => {
      const userManager = await initializeOidcMode()
      useAuthStore.setState({
        token: 'valid-token',
        user: { id: '1', email: 'test@test.com', displayName: 'Test', systemRole: 'USER' as const },
        isAuthenticated: true,
      })

      useAuthStore.getState().expireSession()
      expect(useAuthStore.getState().isAuthenticated).toBe(false)

      const straggler = new User({
        access_token: 'stale-renewed-token',
        token_type: 'Bearer',
        profile: {
          sub: 'user-1',
          iss: 'https://idp.example.test/realms/opaa',
          aud: 'opaa-frontend',
          exp: Math.floor(Date.now() / 1000) + 900,
          iat: Math.floor(Date.now() / 1000),
        },
        expires_at: Math.floor(Date.now() / 1000) + 900,
      })
      await userManager.events.load(straggler)

      expect(useAuthStore.getState().isAuthenticated).toBe(false)
      expect(useAuthStore.getState().user).toBeNull()
    })
  })

  // #737 review, finding 2: expireSession() used to only reset the Zustand store, leaving the
  // UserManager's own session (and its automatic-silent-renew timer) untouched.
  describe('expireSession (#737 review)', () => {
    async function initializeOidcMode() {
      server.use(
        http.get('/api/v1/auth/config', () =>
          HttpResponse.json({
            mode: 'oidc',
            authority: 'https://idp.example.test/realms/opaa',
            clientId: 'opaa-frontend',
          }),
        ),
      )
      await useAuthStore.getState().initialize()
      const { userManager } = useAuthStore.getState()
      if (!userManager) throw new Error('expected a userManager in oidc mode')
      return userManager
    }

    it('never calls signoutRedirect - only a deliberate logout() tears down the IdP session', async () => {
      const userManager = await initializeOidcMode()
      const signoutRedirect = vi.spyOn(userManager, 'signoutRedirect').mockResolvedValue(undefined)

      useAuthStore.getState().expireSession()

      expect(signoutRedirect).not.toHaveBeenCalled()
    })

    it('removes the locally stored OIDC user so a stale automatic renew cannot resurrect the session', async () => {
      const userManager = await initializeOidcMode()
      const removeUser = vi.spyOn(userManager, 'removeUser').mockResolvedValue(undefined)

      useAuthStore.getState().expireSession()

      expect(removeUser).toHaveBeenCalledTimes(1)
    })

    it('sets a German explanation instead of silently clearing the error', () => {
      useAuthStore.setState({ error: null })

      useAuthStore.getState().expireSession()

      expect(useAuthStore.getState().error).toBe(
        'Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.',
      )
    })

    it('resets token, user and isAuthenticated', () => {
      useAuthStore.setState({
        token: 'some-token',
        user: { id: '1', email: 'test@test.com', displayName: 'Test', systemRole: 'USER' as const },
        isAuthenticated: true,
      })

      useAuthStore.getState().expireSession()

      const state = useAuthStore.getState()
      expect(state.token).toBeNull()
      expect(state.user).toBeNull()
      expect(state.isAuthenticated).toBe(false)
    })
  })

  describe('renewToken (#737 review)', () => {
    it('returns false in dev mode without attempting a renew', async () => {
      useAuthStore.setState({ mode: 'dev', userManager: null })

      await expect(useAuthStore.getState().renewToken()).resolves.toBe(false)
    })

    it('returns false when signinSilent fails', async () => {
      server.use(
        http.get('/api/v1/auth/config', () =>
          HttpResponse.json({
            mode: 'oidc',
            authority: 'https://idp.example.test/realms/opaa',
            clientId: 'opaa-frontend',
          }),
        ),
      )
      await useAuthStore.getState().initialize()
      const { userManager } = useAuthStore.getState()
      if (!userManager) throw new Error('expected a userManager in oidc mode')
      vi.spyOn(userManager, 'signinSilent').mockRejectedValue(new Error('renew failed'))

      await expect(useAuthStore.getState().renewToken()).resolves.toBe(false)
    })

    // #737 review (nit): concurrent 401s from background polls used to each start their own
    // signinSilent() call - a refresh-token-rotating IdP would have the first grant invalidate the
    // token for every other in-flight one. renewToken() shares one in-flight promise instead.
    it('shares one in-flight signinSilent call across concurrent callers', async () => {
      server.use(
        http.get('/api/v1/auth/config', () =>
          HttpResponse.json({
            mode: 'oidc',
            authority: 'https://idp.example.test/realms/opaa',
            clientId: 'opaa-frontend',
          }),
        ),
      )
      await useAuthStore.getState().initialize()
      const { userManager } = useAuthStore.getState()
      if (!userManager) throw new Error('expected a userManager in oidc mode')

      const renewedUser = new User({
        access_token: 'renewed-access-token',
        token_type: 'Bearer',
        profile: {
          sub: 'user-1',
          iss: 'https://idp.example.test/realms/opaa',
          aud: 'opaa-frontend',
          exp: Math.floor(Date.now() / 1000) + 900,
          iat: Math.floor(Date.now() / 1000),
        },
        expires_at: Math.floor(Date.now() / 1000) + 900,
      })
      let resolveSignin: (user: User | null) => void = () => {}
      const signinSilent = vi
        .spyOn(userManager, 'signinSilent')
        .mockImplementation(() => new Promise((resolve) => (resolveSignin = resolve)))

      const first = useAuthStore.getState().renewToken()
      const second = useAuthStore.getState().renewToken()
      resolveSignin(renewedUser)

      await expect(first).resolves.toBe(true)
      await expect(second).resolves.toBe(true)
      expect(signinSilent).toHaveBeenCalledTimes(1)

      // A renew started after the in-flight one settled must trigger a fresh signinSilent call -
      // the single-flight guard must not wedge itself permanently.
      const third = useAuthStore.getState().renewToken()
      expect(signinSilent).toHaveBeenCalledTimes(2)
      resolveSignin(null)
      await expect(third).resolves.toBe(false)
    })
  })
})
