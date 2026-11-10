import type { AuthUser } from '../session/types'

/**
 * Who the mock mode signs in, chosen with `?mockProfile=<name>` and remembered in this tab's
 * sessionStorage like the mock scenario. `?mockProfile=admin` returns to the default. Tests never
 * set it and see `admin`.
 *
 * - `admin`: the system administrator of the regular fixtures
 * - `user`: a regular account without administration
 * - `auditor`: the audit role, which sees the revision pages instead of the administration
 * - `signed-out`: nobody; the installation offers two identity providers and local accounts with
 *   self-registration and password reset, so every page before the sign-in is reachable
 */
export const MOCK_PROFILES = ['admin', 'user', 'auditor', 'signed-out'] as const

export type MockProfile = (typeof MOCK_PROFILES)[number]

const STORAGE_KEY = 'opaa.mockProfile'

function isProfile(value: string | null): value is MockProfile {
  return value !== null && (MOCK_PROFILES as readonly string[]).includes(value)
}

/** The active profile: the query parameter wins and is remembered, then the remembered one. */
export function currentMockProfile(): MockProfile {
  if (typeof window === 'undefined') return 'admin'
  try {
    const fromQuery = new URLSearchParams(window.location.search).get('mockProfile')
    if (isProfile(fromQuery)) {
      window.sessionStorage.setItem(STORAGE_KEY, fromQuery)
      return fromQuery
    }
    const stored = window.sessionStorage.getItem(STORAGE_KEY)
    return isProfile(stored) ? stored : 'admin'
  } catch {
    return 'admin'
  }
}

const PROFILE_USERS: Record<Exclude<MockProfile, 'admin' | 'signed-out'>, AuthUser> = {
  user: {
    id: 'mock-profile-user',
    email: 'erika.muster@stadt.example',
    displayName: 'Erika Muster',
    systemRole: 'USER',
    createdReason: 'Projektbefristung Digitalisierung bis 31.12.2026',
  },
  auditor: {
    id: 'mock-profile-auditor',
    email: 'revision@stadt.example',
    displayName: 'Rita Revision',
    systemRole: 'AUDITOR',
  },
}

/** The signed-in user of the profile; `fallback` for `admin`, null when nobody is signed in. */
export function mockProfileUser(profile: MockProfile, fallback: AuthUser): AuthUser | null {
  if (profile === 'signed-out') return null
  return profile === 'admin' ? fallback : PROFILE_USERS[profile]
}
