import type { OidcProviderImpactResponse, OidcProviderResponse } from '../types/api'

export let mockOidcProviders: OidcProviderResponse[] = []

/**
 * What disabling or deleting a provider does to persons' connections, by provider id; a provider
 * missing here has none to confirm - the state while no connector admits persons.
 */
export let mockOidcProviderImpacts: Record<string, OidcProviderImpactResponse> = {}

function initialOidcProviders(): OidcProviderResponse[] {
  return [
    {
      id: 'oidc-provider-beschaeftigte',
      displayName: 'Verzeichnisdienst',
      enabled: true,
      isDefault: true,
      isExternal: false,
      providerType: 'OIDC',
      directorySyncEnabled: false,
      sortOrder: 1,
      issuerUri: 'http://localhost:8180/realms/opaa',
      clientId: 'opaa-frontend',
      jwkSetUri: 'http://keycloak:8180/realms/opaa/protocol/openid-connect/certs',
      claimMapping: {
        emailClaim: 'email',
        displayNameClaim: 'name',
        rolesClaim: null,
        systemAdminRole: null,
        auditorRole: null,
        groupsClaim: null,
      },
      registryState: 'READY',
      registryMessage: null,
      createdAt: '2026-09-01T08:00:00Z',
      updatedAt: '2026-09-01T08:00:00Z',
    },
    {
      id: 'oidc-provider-partner',
      displayName: 'Partnerportal',
      enabled: true,
      isDefault: false,
      isExternal: true,
      providerType: 'OIDC',
      directorySyncEnabled: false,
      sortOrder: 2,
      issuerUri: 'https://partner.example/realms/extern',
      clientId: 'opaa-partner',
      jwkSetUri: null,
      claimMapping: {
        emailClaim: 'upn',
        displayNameClaim: 'name',
        rolesClaim: 'realm_access.roles',
        systemAdminRole: 'opaa-admin',
        auditorRole: null,
        groupsClaim: 'groups',
      },
      registryState: 'UNAVAILABLE',
      registryMessage: 'Discovery-Dokument: Antwort mit HTTP 503.',
      createdAt: '2026-09-02T08:00:00Z',
      updatedAt: '2026-09-02T08:00:00Z',
    },
    {
      id: 'oidc-provider-land',
      displayName: 'Landesportal',
      enabled: true,
      isDefault: false,
      isExternal: true,
      providerType: 'OIDC',
      directorySyncEnabled: false,
      sortOrder: 3,
      issuerUri: 'https://land.example/realms/verwaltung',
      clientId: 'opaa-land',
      jwkSetUri: null,
      claimMapping: {
        emailClaim: 'email',
        displayNameClaim: 'name',
        rolesClaim: null,
        systemAdminRole: null,
        auditorRole: null,
        groupsClaim: null,
      },
      registryState: 'READY',
      registryMessage: null,
      createdAt: '2026-09-03T08:00:00Z',
      updatedAt: '2026-09-03T08:00:00Z',
    },
    // Die eine LOCAL-Zeile (ADR-0033, Entscheidung 4): sie steht in derselben Tabelle und kommt
    // über dieselbe API, ist aber kein Anbieter der Anbieterseite - ihr `enabled` ist der
    // Schalter der lokalen Benutzerverwaltung (#1541). `sortOrder: 0`, weil der Seed sie vor
    // jedem Anbieter anlegt: damit liegt sie in der Sortierung **vor** den Anbietern und das
    // Verschieben muss sie überspringen (Review-Runde 1, MEDIUM 5).
    {
      id: 'oidc-provider-local',
      displayName: 'Lokale Konten',
      enabled: true,
      isDefault: false,
      isExternal: false,
      providerType: 'LOCAL',
      directorySyncEnabled: false,
      sortOrder: 0,
      issuerUri: 'urn:opaa:local',
      clientId: null,
      jwkSetUri: null,
      claimMapping: {
        emailClaim: 'email',
        displayNameClaim: 'name',
        rolesClaim: null,
        systemAdminRole: null,
        auditorRole: null,
        groupsClaim: null,
      },
      registryState: 'READY',
      registryMessage: null,
      createdAt: '2026-09-01T07:00:00Z',
      updatedAt: '2026-09-01T07:00:00Z',
    },
  ]
}

export function resetMockOidcProviders() {
  mockOidcProviders = initialOidcProviders()
  mockOidcProviderImpacts = {}
}
resetMockOidcProviders()
