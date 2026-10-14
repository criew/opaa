import { beforeEach, describe, expect, it } from 'vitest'
import { mockOidcProviders } from '../mocks/identityProviderFixtures'
import { useOidcProviderStore } from './oidcProviderStore'

function ids(): string[] {
  return useOidcProviderStore.getState().providers.map((p) => p.id)
}

describe('oidcProviderStore', () => {
  beforeEach(() => {
    useOidcProviderStore.setState({ providers: [], isLoading: false, error: null })
  })

  it('loads the providers in sign-in order', async () => {
    await useOidcProviderStore.getState().loadProviders()
    // The LOCAL row comes along: the store holds the table, filtering to providers is the
    // provider page's business. The seed creates it before every provider, so it comes first.
    expect(useOidcProviderStore.getState().providers.map((p) => p.displayName)).toEqual([
      'Lokale Konten',
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
    // „Nach oben" on the first provider is a no-op: above it is only the LOCAL row, and it
    // is not swapped with.
    await useOidcProviderStore.getState().moveProvider('oidc-provider-beschaeftigte', 'up')
    expect(ids()).toEqual([
      'oidc-provider-local',
      'oidc-provider-beschaeftigte',
      'oidc-provider-partner',
      'oidc-provider-land',
    ])

    await useOidcProviderStore.getState().moveProvider('oidc-provider-beschaeftigte', 'down')
    expect(ids()).toEqual([
      'oidc-provider-local',
      'oidc-provider-partner',
      'oidc-provider-beschaeftigte',
      'oidc-provider-land',
    ])
    expect(mockOidcProviders.find((p) => p.id === 'oidc-provider-local')?.sortOrder).toBe(0)
  })

  /**
   * If the LOCAL row lies between two providers, „nach unten" does not swap with it — otherwise
   * the visible order would stay the same, the click swallowed and the order event
   * without effect.
   */
  it('skips the local row when it lies between two providers', async () => {
    const order: Record<string, number> = {
      'oidc-provider-beschaeftigte': 1,
      'oidc-provider-partner': 2,
      'oidc-provider-local': 3,
      'oidc-provider-land': 4,
    }
    mockOidcProviders.forEach((provider) => {
      provider.sortOrder = order[provider.id] ?? provider.sortOrder
    })
    await useOidcProviderStore.getState().loadProviders()
    expect(ids()[2]).toBe('oidc-provider-local')

    await useOidcProviderStore.getState().moveProvider('oidc-provider-partner', 'down')

    expect(ids()).toEqual([
      'oidc-provider-beschaeftigte',
      'oidc-provider-land',
      'oidc-provider-local',
      'oidc-provider-partner',
    ])
  })

  it('never moves the local row itself', async () => {
    await useOidcProviderStore.getState().loadProviders()
    await useOidcProviderStore.getState().moveProvider('oidc-provider-local', 'down')
    expect(ids()).toEqual([
      'oidc-provider-local',
      'oidc-provider-beschaeftigte',
      'oidc-provider-partner',
      'oidc-provider-land',
    ])
  })

  it('removes a deleted provider locally', async () => {
    await useOidcProviderStore.getState().loadProviders()
    await useOidcProviderStore.getState().deleteExistingProvider('oidc-provider-partner')
    expect(ids()).toEqual([
      'oidc-provider-local',
      'oidc-provider-beschaeftigte',
      'oidc-provider-land',
    ])
  })
})
