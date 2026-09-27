import type { AuthConfig, AuthUser, LocalAccountsConfig, LocalTokenResponse } from '../types/auth'

/**
 * The local account management as the mocks present it by default: switched off, like a fresh
 * installation (ADR-0033, Entscheidung 4). Tests that need the mask switch it on with
 * {@link setMockLocalAccounts}.
 */
export const mockLocalAccountsConfig: LocalAccountsConfig = {
  enabled: false,
  selfRegistrationEnabled: false,
  passwordResetEnabled: false,
  passwordMinLength: 12,
}

export let mockAuthConfig: AuthConfig = {
  mode: 'dev',
  providers: [],
  localAccounts: mockLocalAccountsConfig,
}

/** Switches the mocked local account management, e.g. to show the password mask. */
export function setMockLocalAccounts(localAccounts: Partial<LocalAccountsConfig>) {
  mockAuthConfig = {
    ...mockAuthConfig,
    localAccounts: {
      ...mockLocalAccountsConfig,
      ...mockAuthConfig.localAccounts,
      ...localAccounts,
    },
  }
}

/** Puts the mocked auth config back to the default of a dev-mode installation. */
export function resetMockAuthConfig() {
  mockAuthConfig = { mode: 'dev', providers: [], localAccounts: mockLocalAccountsConfig }
}

/** The credentials the mocked local sign-in accepts; everything else is refused with 401. */
export const mockLocalAccount = {
  email: 'erika.muster@stadt.example',
  password: 'Mustergueltig-2026',
}

/** A minted local session as POST /api/v1/auth/local/{login,refresh,change-password} returns it. */
export const mockLocalTokenResponse: LocalTokenResponse = {
  accessToken: 'mock-local-access-token',
  expiresInSeconds: 900,
  passwordChangeRequired: false,
}

export const mockUser: AuthUser = {
  id: 'mock-user-id',
  email: 'admin@opaa.local',
  displayName: 'Admin',
  systemRole: 'SYSTEM_ADMIN',
}
