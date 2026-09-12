import { beforeEach, describe, expect, it } from 'vitest'
import { mockOidcProviders } from '../mocks/fixtures'
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
    // Die LOCAL-Zeile kommt mit: der Store führt die Tabelle, das Filtern auf Anbieter ist Sache
    // der Anbieterseite (#1541). Der Seed legt sie vor jedem Anbieter an, sie steht also vorn.
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
    // „Nach oben" am ersten Anbieter ist ein No-Op: darüber liegt nur die LOCAL-Zeile, und mit
    // ihr wird nicht getauscht (#1541).
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
   * Regressionsschutz zu Review-Runde 1 (MEDIUM 5): Lag die LOCAL-Zeile zwischen zwei Anbietern,
   * tauschte „nach unten" mit ihr — die sichtbare Reihenfolge blieb gleich, der Klick war
   * verschluckt und das Reihenfolge-Ereignis wirkungslos.
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
