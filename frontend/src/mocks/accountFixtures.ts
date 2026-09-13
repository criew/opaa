import type { AccountProviderResponse, AccountResponse, LocalUserResponse } from '../types/api'

/**
 * Fixtures der Kontenliste (#1601): die Anbieterkonten, die neben den lokalen Konten aus
 * `localUserFixtures.ts` erscheinen - zwei Anbieter, einer davon führt die Rollen über einen
 * Claim, und ein Konto, dessen Anbieterzeile inzwischen gelöscht ist (ADR-0025: das Konto bleibt).
 */

export const DIRECTORY_PROVIDER: AccountProviderResponse = {
  id: 'provider-directory',
  displayName: 'Verzeichnisdienst',
  enabled: true,
}

export const PARTNER_PROVIDER: AccountProviderResponse = {
  id: 'provider-partner',
  displayName: 'Partnerportal',
  enabled: true,
}

/** The partner's account whose role the partner's roles claim manages - a role change answers 409. */
export const ROLE_MANAGED_USER_ID = 'oidc-user-partner-admin'

/** The account whose provider row is gone: no provider, the issuer kept. */
export const PROVIDER_WITHOUT_ROW_USER_ID = 'oidc-user-ghost'

function initialProviderAccounts(): AccountResponse[] {
  return [
    {
      id: 'oidc-user-weber',
      email: 'maria.weber@stadt.example',
      displayName: 'Maria Weber',
      systemRole: 'USER',
      providerType: 'OIDC',
      issuer: 'http://localhost:8180/realms/opaa',
      roleManagedByProvider: false,
      createdAt: '2026-09-11T09:00:00Z',
      provider: DIRECTORY_PROVIDER,
    },
    {
      id: 'oidc-user-klein',
      email: 'thomas.klein@stadt.example',
      displayName: 'Thomas Klein',
      systemRole: 'USER',
      providerType: 'OIDC',
      issuer: 'http://localhost:8180/realms/opaa',
      roleManagedByProvider: false,
      createdAt: '2026-09-11T09:05:00Z',
      provider: DIRECTORY_PROVIDER,
    },
    {
      id: ROLE_MANAGED_USER_ID,
      email: 'p.admin@partner.example',
      displayName: 'P. Admin (Partner)',
      systemRole: 'SYSTEM_ADMIN',
      providerType: 'OIDC',
      issuer: 'http://localhost:8180/realms/partner',
      roleManagedByProvider: true,
      createdAt: '2026-09-02T14:00:00Z',
      provider: PARTNER_PROVIDER,
    },
    {
      id: PROVIDER_WITHOUT_ROW_USER_ID,
      email: 'alte.anbieterin@extern.example',
      displayName: 'Alte Anbieterin',
      systemRole: 'USER',
      providerType: 'OIDC',
      issuer: 'https://gone.example/realms/alt',
      roleManagedByProvider: false,
      createdAt: '2026-04-10T10:00:00Z',
    },
  ]
}

export let mockProviderAccounts: AccountResponse[] = initialProviderAccounts()

export function setMockProviderAccounts(accounts: AccountResponse[]) {
  mockProviderAccounts = accounts
}

export function resetMockProviderAccounts() {
  mockProviderAccounts = initialProviderAccounts()
}

/** A local account row of `localUserFixtures.ts` as the account list returns it. */
export function toLocalAccount(local: LocalUserResponse): AccountResponse {
  return {
    id: local.id,
    email: local.email,
    displayName: local.displayName,
    systemRole: local.systemRole,
    providerType: 'LOCAL',
    issuer: 'urn:opaa:local',
    roleManagedByProvider: false,
    createdAt: local.createdAt,
    local,
  }
}
