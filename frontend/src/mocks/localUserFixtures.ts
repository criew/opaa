import type { LocalAuthSettingsResponse, LocalUserResponse } from '../types/api'

/**
 * Fixtures der Benutzerverwaltung (#1541, ADR-0033 Entscheidungen 4 und 11) – in einem eigenen
 * Modul wie die Mail-Fixtures (#1542), weil `fixtures.ts` längst über der 800-Zeilen-Marke liegt,
 * und veränderlich, weil die Seite nur etwas wert ist, wenn eine Handlung auf dem nächsten GET
 * sichtbar ist.
 */

/** The signed-in account of the mock (`mockUser.id`) - its own row refuses lock and delete. */
export const MOCK_SELF_USER_ID = 'mock-user-id'

/**
 * An address whose invitation and reset mail fails in the mock, so the link-fallback path - the
 * one-time display of the URL - is reachable from a test and from the dev server.
 */
export const MAIL_FAILING_ADDRESS_SUFFIX = '@kaputt.example'

/** The account that still owns content: its deletion answers 409 `ACCOUNT_OWNS_CONTENT`. */
export const OWNS_CONTENT_USER_ID = 'local-user-weber'

/**
 * The second system administrator of the mock. Locking it or taking its role away answers 409
 * `LAST_LOGIN_CAPABLE_ADMIN` - the mock's way of making the guard's refusal reachable, which in a
 * real installation depends on who else can actually sign in (ADR-0033, Entscheidung 4).
 */
export const LAST_ADMIN_USER_ID = 'local-user-hoffmann'

function initialLocalUsers(): LocalUserResponse[] {
  return [
    {
      id: MOCK_SELF_USER_ID,
      email: 'admin@opaa.local',
      displayName: 'Systemverwaltung',
      systemRole: 'SYSTEM_ADMIN',
      status: 'ACTIVE',
      passwordChangeRequired: false,
      createdReason: 'Notanker-Konto der Systemverwaltung',
      createdAt: '2026-01-08T07:30:00Z',
      activity: 'ACTIVE',
      bootstrap: true,
    },
    {
      id: OWNS_CONTENT_USER_ID,
      email: 'm.weber@partner.example',
      displayName: 'M. Weber (Partner)',
      systemRole: 'USER',
      status: 'ACTIVE',
      passwordChangeRequired: false,
      createdReason: 'Begleitung des Projekts Aktenplan, unbefristet bis zur Klärung der Laufzeit',
      createdAt: '2026-03-02T09:10:00Z',
      activity: 'ACTIVE',
      bootstrap: false,
    },
    {
      id: 'local-user-klein',
      email: 't.klein@stadt.example',
      displayName: 'T. Klein',
      systemRole: 'USER',
      status: 'INVITED',
      passwordChangeRequired: false,
      expiresAt: '2026-12-31T22:59:59Z',
      createdReason: 'Vertretung im Bauamt bis zum Jahresende',
      createdAt: '2026-09-01T06:45:00Z',
      activity: 'NEVER',
      bootstrap: false,
    },
    {
      id: 'local-user-vogt',
      email: 'a.vogt@stadt.example',
      displayName: 'A. Vogt',
      systemRole: 'USER',
      status: 'LOCKED',
      lockedReason: 'ADMIN',
      passwordChangeRequired: false,
      createdReason: 'Projektmitarbeit Digitalisierung',
      createdAt: '2026-02-11T11:00:00Z',
      activity: 'INACTIVE_90_DAYS',
      bootstrap: false,
    },
    {
      id: 'local-user-sommer',
      email: 'r.sommer@stadt.example',
      displayName: 'R. Sommer',
      systemRole: 'USER',
      status: 'EXPIRED',
      passwordChangeRequired: false,
      expiresAt: '2026-06-30T21:59:59Z',
      createdReason: 'Praktikum im Personalamt',
      createdAt: '2026-04-01T08:00:00Z',
      activity: 'INACTIVE_90_DAYS',
      bootstrap: false,
    },
    {
      id: 'local-user-ernst',
      email: 'k.ernst@stadt.example',
      displayName: 'K. Ernst',
      systemRole: 'AUDITOR',
      status: 'ACTIVE',
      passwordChangeRequired: true,
      passwordChangeReason: 'INITIAL',
      expiresAt: '2027-03-31T21:59:59Z',
      createdReason: 'Revision, befristet auf die Prüfperiode',
      createdAt: '2026-08-20T12:20:00Z',
      activity: 'NEVER',
      bootstrap: false,
    },
    {
      id: LAST_ADMIN_USER_ID,
      email: 'j.hoffmann@stadt.example',
      displayName: 'J. Hoffmann',
      systemRole: 'SYSTEM_ADMIN',
      status: 'ACTIVE',
      passwordChangeRequired: false,
      expiresAt: '2027-06-30T21:59:59Z',
      createdReason: 'Zweite Systemverwaltung nach dem Vier-Augen-Prinzip',
      createdAt: '2026-05-14T10:05:00Z',
      activity: 'ACTIVE',
      bootstrap: false,
    },
  ]
}

export let mockLocalUsers: LocalUserResponse[] = initialLocalUsers()

export function setMockLocalUsers(users: LocalUserResponse[]) {
  mockLocalUsers = users
}

export function resetMockLocalUsers() {
  mockLocalUsers = initialLocalUsers()
}

function initialLocalAuthSettings(): LocalAuthSettingsResponse {
  return {
    enabled: true,
    selfRegistrationEnabled: false,
    selfRegistrationAllowedDomains: ['stadt.example'],
    passwordResetEnabled: true,
    passwordMinLength: 12,
    invitationTokenTtlHours: 72,
    resetTokenTtlMinutes: 60,
    defaultExpiryDays: 365,
    inactiveDays: 90,
    publicBaseUrlConfigured: true,
  }
}

export let mockLocalAuthSettings: LocalAuthSettingsResponse = initialLocalAuthSettings()

export function setMockLocalAuthSettings(settings: LocalAuthSettingsResponse) {
  mockLocalAuthSettings = settings
}

export function resetMockLocalAuthSettings() {
  mockLocalAuthSettings = initialLocalAuthSettings()
}
