import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockAuthConfig, setMockFeatures } from '../mocks/authFixtures'
import { isFeatureEnabled, useAuthStore } from './authStore'

/**
 * The installation-wide feature flags (ADR-0044) arrive with `GET /api/v1/auth/config`: readable
 * before any sign-in, kept after it, and every flag the answer does not name counts as on.
 */
describe('authStore feature flags', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: true,
      error: null,
      providers: [],
      userManager: null,
      activeProviderId: null,
      features: {},
    })
  })

  it('counts every flag as on before the configuration has arrived', () => {
    expect(isFeatureEnabled('promptLibraries')).toBe(true)
    expect(isFeatureEnabled('metadata')).toBe(true)
  })

  it('takes the flags from the configuration and keeps them for the signed-in session', async () => {
    setMockFeatures({ metadata: false })

    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(true)
    expect(state.features).toEqual({ promptLibraries: true, metadata: false, speechInput: true })
    expect(isFeatureEnabled('metadata')).toBe(false)
    expect(isFeatureEnabled('promptLibraries')).toBe(true)
  })

  it('knows the flags before any sign-in in the oidc mode', async () => {
    server.use(
      http.get('/api/v1/auth/config', () =>
        HttpResponse.json({
          ...mockAuthConfig,
          mode: 'oidc',
          providers: [],
          features: { promptLibraries: false, metadata: true },
        }),
      ),
    )

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(isFeatureEnabled('promptLibraries')).toBe(false)
    expect(isFeatureEnabled('metadata')).toBe(true)
  })

  it('counts every flag as on when the backend answers without a features block', async () => {
    server.use(
      http.get('/api/v1/auth/config', () =>
        HttpResponse.json({
          mode: 'dev',
          providers: [],
          localAccounts: mockAuthConfig.localAccounts,
        }),
      ),
    )
    useAuthStore.setState({ features: { metadata: false } })

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().features).toEqual({})
    expect(isFeatureEnabled('metadata')).toBe(true)
    expect(isFeatureEnabled('promptLibraries')).toBe(true)
  })

  it('re-reads the flags with the public configuration', async () => {
    await useAuthStore.getState().initialize()
    expect(isFeatureEnabled('promptLibraries')).toBe(true)

    setMockFeatures({ promptLibraries: false })
    await useAuthStore.getState().refreshPublicAuthConfig()

    expect(isFeatureEnabled('promptLibraries')).toBe(false)
  })
})
