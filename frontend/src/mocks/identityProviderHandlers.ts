import { http, HttpResponse } from 'msw'
import { mockOidcProviders } from './identityProviderFixtures'
import type {
  OidcProviderOrderRequest,
  OidcProviderRequest,
  OidcProviderResponse,
  OidcProviderTestRequest,
} from '../types/api'

const disabledRegistryStates = new Map<string, OidcProviderResponse['registryState']>()

/** Die OIDC-Zeilen außer `providerId`; die LOCAL-Zeile ist kein Anbieter dieser Regeln. */
function otherOidcProviders(providerId: string) {
  return mockOidcProviders.filter((p) => p.id !== providerId && p.providerType === 'OIDC')
}

/** `acknowledgeLastProvider=true` aus der Anfrage (ADR-0033, Entscheidung 4). */
function acknowledgesLastProvider(request: Request): boolean {
  return new URL(request.url).searchParams.get('acknowledgeLastProvider') === 'true'
}

export const identityProviderHandlers = [
  // identity providers (ADR-0025, #1329 admin API) - public clients, no secret in any payload;
  // (disabledRegistryStates remembers a disabled provider's decoder state until it is re-enabled)
  // the same invariants as OidcProviderService: the default is neither disable- nor deletable
  // while another provider is there, the last enabled one only with acknowledgeLastProvider
  // (ADR-0033, Entscheidung 4), a duplicate issuer is a conflict.
  http.get('/api/v1/admin/oidc-providers', () => {
    return HttpResponse.json([...mockOidcProviders].sort((a, b) => a.sortOrder - b.sortOrder))
  }),

  http.post('/api/v1/admin/oidc-providers', async ({ request }) => {
    const body = (await request.json()) as OidcProviderRequest
    if (!body.displayName || !body.issuerUri || !body.clientId) {
      return HttpResponse.json(
        { error: 'Anzeigename, Issuer-URI und Client-ID sind erforderlich' },
        { status: 400 },
      )
    }
    if (mockOidcProviders.some((p) => p.issuerUri === body.issuerUri)) {
      return HttpResponse.json(
        { error: 'Für diesen Issuer existiert bereits ein Anbieter.' },
        { status: 409 },
      )
    }
    const now = new Date().toISOString()
    const created: OidcProviderResponse = {
      id: `oidc-provider-${crypto.randomUUID().slice(0, 8)}`,
      displayName: body.displayName,
      enabled: true,
      isDefault: mockOidcProviders.length === 0,
      // Vorgabe aus ADR-0036, Entscheidung 2: alles außer dem Standardanbieter ist extern
      isExternal: mockOidcProviders.length > 0,
      providerType: 'OIDC',
      directorySyncEnabled: false,
      sortOrder: mockOidcProviders.length,
      issuerUri: body.issuerUri,
      clientId: body.clientId,
      jwkSetUri: body.jwkSetUri ?? null,
      claimMapping: {
        emailClaim: body.claimMapping?.emailClaim || 'email',
        displayNameClaim: body.claimMapping?.displayNameClaim || 'name',
        rolesClaim: body.claimMapping?.rolesClaim || null,
        systemAdminRole: body.claimMapping?.systemAdminRole || null,
        auditorRole: body.claimMapping?.auditorRole || null,
        groupsClaim: body.claimMapping?.groupsClaim || null,
      },
      registryState: 'READY',
      registryMessage: null,
      createdAt: now,
      updatedAt: now,
    }
    mockOidcProviders.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.put('/api/v1/admin/oidc-providers/order', async ({ request }) => {
    const body = (await request.json()) as OidcProviderOrderRequest
    body.providerIds.forEach((id, index) => {
      const provider = mockOidcProviders.find((p) => p.id === id)
      if (provider) provider.sortOrder = index
    })
    return HttpResponse.json([...mockOidcProviders].sort((a, b) => a.sortOrder - b.sortOrder))
  }),

  http.post('/api/v1/admin/oidc-providers/test', async ({ request }) => {
    const body = (await request.json()) as OidcProviderTestRequest
    if (!body.issuerUri) {
      return HttpResponse.json({ error: 'Issuer-URI darf nicht leer sein.' }, { status: 400 })
    }
    return HttpResponse.json({
      success: true,
      message: 'Anbieter erreichbar: Discovery-Dokument gefunden, JWK-Set mit 2 Schlüsseln.',
    })
  }),

  http.put('/api/v1/admin/oidc-providers/:providerId', async ({ params, request }) => {
    const provider = mockOidcProviders.find((p) => p.id === String(params.providerId))
    if (!provider) {
      return HttpResponse.json({ error: 'Anbieter nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as OidcProviderRequest
    if (body.issuerUri !== provider.issuerUri && provider.id === 'oidc-provider-beschaeftigte') {
      return HttpResponse.json(
        {
          error:
            'Die Issuer-URI kann nicht geändert werden: Über diesen Anbieter wurden bereits 12' +
            ' Konten angelegt, die ihre Identität verlieren würden.',
        },
        { status: 409 },
      )
    }
    provider.displayName = body.displayName
    provider.issuerUri = body.issuerUri
    provider.clientId = body.clientId
    provider.jwkSetUri = body.jwkSetUri ?? null
    provider.claimMapping = {
      emailClaim: body.claimMapping?.emailClaim || 'email',
      displayNameClaim: body.claimMapping?.displayNameClaim || 'name',
      rolesClaim: body.claimMapping?.rolesClaim || null,
      systemAdminRole: body.claimMapping?.systemAdminRole || null,
      auditorRole: body.claimMapping?.auditorRole || null,
      groupsClaim: body.claimMapping?.groupsClaim || null,
    }
    provider.updatedAt = new Date().toISOString()
    return HttpResponse.json(provider)
  }),

  http.delete('/api/v1/admin/oidc-providers/:providerId', ({ params, request }) => {
    const provider = mockOidcProviders.find((p) => p.id === String(params.providerId))
    if (!provider) {
      return HttpResponse.json({ error: 'Anbieter nicht gefunden' }, { status: 404 })
    }
    if (provider.providerType === 'LOCAL') {
      return HttpResponse.json(
        { error: 'Die lokale Benutzerverwaltung kann nicht gelöscht werden.', code: 'LOCAL_ROW' },
        { status: 409 },
      )
    }
    const others = otherOidcProviders(provider.id)
    if (provider.isDefault && others.length > 0) {
      return HttpResponse.json(
        { error: 'Der Standardanbieter kann nicht gelöscht werden.' },
        { status: 409 },
      )
    }
    // ADR-0033, Entscheidung 4: der letzte aktivierte Anbieter nur mit ausdrücklicher Bestätigung.
    if (provider.enabled && !others.some((p) => p.enabled) && !acknowledgesLastProvider(request)) {
      return HttpResponse.json(
        {
          error: 'Danach können sich nur noch lokale Konten anmelden – bitte bestätigen.',
          code: 'LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED',
        },
        { status: 409 },
      )
    }
    mockOidcProviders.splice(mockOidcProviders.indexOf(provider), 1)
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/oidc-providers/:providerId/enable', ({ params }) => {
    const provider = mockOidcProviders.find((p) => p.id === String(params.providerId))
    if (!provider) {
      return HttpResponse.json({ error: 'Anbieter nicht gefunden' }, { status: 404 })
    }
    provider.enabled = true
    // the decoder state a re-enabled provider comes back with is whatever it was before
    provider.registryState = disabledRegistryStates.get(provider.id) ?? 'READY'
    provider.registryMessage = provider.registryState === 'READY' ? null : provider.registryMessage
    return HttpResponse.json(provider)
  }),

  http.post('/api/v1/admin/oidc-providers/:providerId/disable', ({ params, request }) => {
    const provider = mockOidcProviders.find((p) => p.id === String(params.providerId))
    if (!provider) {
      return HttpResponse.json({ error: 'Anbieter nicht gefunden' }, { status: 404 })
    }
    const others = otherOidcProviders(provider.id)
    if (provider.isDefault && others.some((p) => p.enabled)) {
      return HttpResponse.json(
        { error: 'Der Standardanbieter kann nicht deaktiviert werden.' },
        { status: 409 },
      )
    }
    if (provider.enabled && !others.some((p) => p.enabled) && !acknowledgesLastProvider(request)) {
      return HttpResponse.json(
        {
          error: 'Danach können sich nur noch lokale Konten anmelden – bitte bestätigen.',
          code: 'LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED',
        },
        { status: 409 },
      )
    }
    provider.enabled = false
    disabledRegistryStates.set(provider.id, provider.registryState)
    provider.registryState = 'DISABLED'
    return HttpResponse.json(provider)
  }),

  http.post('/api/v1/admin/oidc-providers/:providerId/default', ({ params }) => {
    const provider = mockOidcProviders.find((p) => p.id === String(params.providerId))
    if (!provider) {
      return HttpResponse.json({ error: 'Anbieter nicht gefunden' }, { status: 404 })
    }
    if (!provider.enabled) {
      return HttpResponse.json(
        { error: 'Ein deaktivierter Anbieter kann nicht Standardanbieter werden.' },
        { status: 409 },
      )
    }
    // OidcProviderService#makeDefault: the default must be reachable, there is no state without
    // a sign-in-capable provider
    if (provider.registryState !== 'READY') {
      return HttpResponse.json(
        {
          error:
            'Ein Anbieter, dessen Schlüssel nicht abrufbar sind, kann nicht Standardanbieter' +
            ` werden: ${provider.registryMessage ?? ''}. Beheben Sie die Verbindung zuerst.`,
        },
        { status: 409 },
      )
    }
    mockOidcProviders.forEach((p) => {
      p.isDefault = p.id === provider.id
    })
    return HttpResponse.json(provider)
  }),
]
