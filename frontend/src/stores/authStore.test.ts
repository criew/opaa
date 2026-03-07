import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore } from './authStore'

describe('authStore', () => {
  beforeEach(() => {
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

  it('initializes as authenticated in mock mode', async () => {
    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.mode).toBe('mock')
    expect(state.isAuthenticated).toBe(true)
    expect(state.isLoading).toBe(false)
  })

  it('logs in with basic credentials', async () => {
    useAuthStore.setState({ mode: 'basic', isLoading: false })

    await useAuthStore.getState().loginBasic('admin', 'admin')

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(true)
    expect(state.token).toBe('mock-jwt-token')
    expect(state.user).toEqual({
      id: 'mock-user-id',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole: 'USER',
    })
  })

  it('shows error on invalid basic login', async () => {
    useAuthStore.setState({ mode: 'basic', isLoading: false })

    await useAuthStore.getState().loginBasic('admin', 'wrong')

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.error).toBeTruthy()
  })

  it('clears state on logout', async () => {
    useAuthStore.setState({
      mode: 'basic',
      token: 'some-token',
      user: { id: '1', email: 'test@test.com', displayName: 'Test', systemRole: 'USER' as const },
      isAuthenticated: true,
      isLoading: false,
    })

    await useAuthStore.getState().logout()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.token).toBeNull()
    expect(state.user).toBeNull()
  })

  it('returns access token via getAccessToken', () => {
    useAuthStore.setState({ token: 'test-token' })
    expect(useAuthStore.getState().getAccessToken()).toBe('test-token')
  })
})
