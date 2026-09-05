import { beforeEach, describe, expect, it } from 'vitest'
import { mockOidcProviders } from '../mocks/fixtures'
import { useOidcProviderStore } from './oidcProviderStore'

describe('oidcProviderStore', () => {
  beforeEach(() => {
    useOidcProviderStore.setState({ providers: [], isLoading: false, error: null })
  })

  it('loads the providers in sign-in order', async () => {
    await useOidcProviderStore.getState().loadProviders()
    expect(useOidcProviderStore.getState().providers.map((p) => p.displayName)).toEqual([
      'Verzeichnisdienst',
      'Partnerportal',
      'Landesportal',
    ])
  })

  it('moves the default flag in one step and keeps the list sorted', async () => {
    await useOidcProviderStore.getState().loadProviders()
    await useOidcProviderStore.getState().makeProviderDefault('oidc-provider-land')
    const providers = useOidcProviderStore.getState().providers
    expect(providers.filter((p) => p.isDefault).map((p) => p.id)).toEqual(['oidc-provider-land'])
  })

  it('swaps positions when moving and ignores a move past the ends', async () => {
    await useOidcProviderStore.getState().loadProviders()
    await useOidcProviderStore.getState().moveProvider('oidc-provider-beschaeftigte', 'up')
    expect(useOidcProviderStore.getState().providers[0].id).toBe('oidc-provider-beschaeftigte')
    await useOidcProviderStore.getState().moveProvider('oidc-provider-beschaeftigte', 'down')
    expect(useOidcProviderStore.getState().providers.map((p) => p.id)).toEqual([
      'oidc-provider-partner',
      'oidc-provider-beschaeftigte',
      'oidc-provider-land',
    ])
    expect(mockOidcProviders.find((p) => p.id === 'oidc-provider-partner')?.sortOrder).toBe(0)
  })

  it('removes a deleted provider locally', async () => {
    await useOidcProviderStore.getState().loadProviders()
    await useOidcProviderStore.getState().deleteExistingProvider('oidc-provider-partner')
    expect(useOidcProviderStore.getState().providers.map((p) => p.id)).toEqual([
      'oidc-provider-beschaeftigte',
      'oidc-provider-land',
    ])
  })
})
