import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { useAuthStore } from './authStore'
import { useSpaceStore } from './spaceStore'

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
          kind: 'PERSONAL',
          visibility: 'PRIVATE',
          memberCount: 1,
          userRole: 'ADMIN',
          createdAt: '',
          updatedAt: '',
        },
      ],
      selectedSpaceId: 'space-1',
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
  })

  it('returns access token via getAccessToken', () => {
    useAuthStore.setState({ token: 'test-token' })
    expect(useAuthStore.getState().getAccessToken()).toBe('test-token')
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
})
