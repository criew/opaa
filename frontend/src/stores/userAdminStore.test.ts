import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import {
  LAST_ADMIN_USER_ID,
  MAIL_FAILING_ADDRESS_SUFFIX,
  OWNS_CONTENT_USER_ID,
  mockLocalUsers,
} from '../mocks/localUserFixtures'
import {
  PARTNER_PROVIDER,
  ROLE_MANAGED_USER_ID,
  mockProviderAccounts,
  resetMockProviderAccounts,
} from '../mocks/accountFixtures'
import { apiErrorCode, apiFieldErrors } from '../services/apiErrorDetails'
import { resetAllStores } from './resettableStores'
import { INITIAL_ACCOUNT_FILTERS, useUserAdminStore } from './userAdminStore'

function state() {
  return useUserAdminStore.getState()
}

describe('userAdminStore', () => {
  beforeEach(() => {
    state().reset()
    resetMockProviderAccounts()
  })

  it('loads the first page of every account with the default sort', async () => {
    await state().loadAccounts()
    expect(state().filters).toEqual(INITIAL_ACCOUNT_FILTERS)
    expect(state().total).toBe(mockLocalUsers.length + mockProviderAccounts.length)
    // displayName ascending: „A. Vogt" first - local and provider accounts in one order
    expect(state().accounts[0].displayName).toBe('A. Vogt')
    expect(state().accounts.some((account) => account.providerType === 'OIDC')).toBe(true)
    expect(state().error).toBeNull()
  })

  it('passes the review filters to the API and returns to the first page', async () => {
    await state().setFilters({ page: 1 })
    expect(state().filters.page).toBe(1)

    await state().setFilters({ review: 'WITHOUT_EXPIRY' })
    expect(state().filters.page).toBe(0)
    expect(state().accounts.every((account) => account.local && !account.local.expiresAt)).toBe(
      true,
    )

    await state().setFilters({ review: 'ALL', status: 'INVITED' })
    expect(state().accounts.map((account) => account.displayName)).toEqual(['T. Klein'])
  })

  it('ranks origin, role and state instead of ordering their words alphabetically', async () => {
    await state().setFilters({ sort: 'origin' })
    const origins = state().accounts.map((a) => a.providerType)
    expect(origins[0]).toBe('LOCAL')
    expect(origins.at(-1)).toBe('OIDC')
    // das Konto ohne Anbieterzeile steht hinter denen mit einer
    expect(state().accounts.at(-1)?.provider).toBeUndefined()

    await state().setFilters({ sort: 'role' })
    expect(state().accounts[0].systemRole).toBe('USER')
    expect(state().accounts.at(-1)?.systemRole).toBe('SYSTEM_ADMIN')

    await state().setFilters({ sort: 'status' })
    expect(state().accounts[0].local?.status).toBe('LOCKED')
    expect(state().accounts.at(-1)?.local).toBeUndefined()

    await state().setFilters({ sort: 'status', direction: 'desc' })
    expect(state().accounts[0].local).toBeUndefined()
    // Die Gleichstandsregel bleibt aufsteigend, auch wenn die Primärordnung umgedreht ist — wie im
    // Backend (AccountAdminService#comparator). Sonst lieferten Testdoppel und Backend für
    // dieselbe Anfrage zwei Reihenfolgen.
    const ohneZustand = state()
      .accounts.filter((account) => !account.local)
      .map((account) => account.displayName ?? '')
    expect(ohneZustand).toEqual([...ohneZustand].sort((a, b) => a.localeCompare(b, 'de-DE')))
  })

  it('sorts only by the seven allow-listed fields', async () => {
    await state().setFilters({ sort: 'email', direction: 'desc' })
    const emails = state().accounts.map((account) => account.email)
    expect(emails).toEqual([...emails].sort().reverse())
  })

  it('adopts a locked account from its own response and refreshes the review counts', async () => {
    await state().loadAccounts()
    await state().loadSummary()
    const lockedBefore = state().summary?.locked ?? 0

    const updated = await state().lockUser('local-user-klein')

    expect(updated.status).toBe('LOCKED')
    expect(
      state().accounts.find((account) => account.id === 'local-user-klein')?.local?.status,
    ).toBe('LOCKED')
    expect(state().summary?.locked).toBe(lockedBefore + 1)
  })

  it('reloads the page after creating an account and carries the one-time secret through', async () => {
    await state().loadAccounts()
    const before = state().total

    const created = await state().createUser({
      email: 'neu@stadt.example',
      displayName: 'N. Neu',
      createdReason: 'Neueinstellung im Bauamt',
      noExpiry: false,
      mode: 'INITIAL_PASSWORD',
    })

    expect(created.initialPassword).toBeTruthy()
    expect(state().total).toBe(before + 1)
  })

  it('returns the setup link exactly when the invitation mail did not go out', async () => {
    const created = await state().createUser({
      email: `extern${MAIL_FAILING_ADDRESS_SUFFIX}`,
      displayName: 'E. Extern',
      createdReason: 'Externe Begleitung der Prüfung',
      noExpiry: true,
      mode: 'INVITE',
    })
    expect(created.emailSent).toBe(false)
    expect(created.deliveryPath).toBe('MAIL_FAILED')
    expect(created.setupUrl).toContain('token=')

    const sent = await state().createUser({
      email: 'intern@stadt.example',
      displayName: 'I. Intern',
      createdReason: 'Vertretung im Ordnungsamt',
      noExpiry: false,
      mode: 'INVITE',
    })
    expect(sent.emailSent).toBe(true)
    expect(sent.setupUrl).toBeUndefined()
  })

  it('keeps the conflict code of a refused act reachable for the curated message', async () => {
    await state().loadAccounts()
    await expect(state().deleteUser(OWNS_CONTENT_USER_ID)).rejects.toSatisfy(
      (err: unknown) => apiErrorCode(err) === 'ACCOUNT_OWNS_CONTENT',
    )
    await expect(state().lockUser(LAST_ADMIN_USER_ID)).rejects.toSatisfy(
      (err: unknown) => apiErrorCode(err) === 'LAST_LOGIN_CAPABLE_ADMIN',
    )
  })

  /**
   * Nachprüfung N2: Der Epoch-Schutz einer Mutation muss die Epoche **vor** dem Request fassen.
   * Wurde sie erst danach gefasst, war sie immer die neue, und eine Antwort nach einer Abmeldung
   * füllte den gerade geleerten Store erneut - gemessen an `summary`.
   */
  it('does not write a mutation answer back into a store that was reset meanwhile', async () => {
    await state().loadAccounts()
    await state().loadSummary()
    expect(state().summary).not.toBeNull()

    server.use(
      http.post('/api/v1/admin/local-users/:id/lock', async ({ params }) => {
        // Die Abmeldung fällt zwischen Anfrage und Antwort - genau das Fenster, das der Schutz
        // abdecken muss.
        resetAllStores()
        const locked = mockLocalUsers.find((account) => account.id === String(params.id))!
        return HttpResponse.json({ ...locked, status: 'LOCKED', lockedReason: 'ADMIN' })
      }),
    )

    await state().lockUser('local-user-klein')

    expect(state().accounts).toEqual([])
    expect(state().summary).toBeNull()
  })

  it('reports a failed list load without emptying what is already shown', async () => {
    await state().loadAccounts()
    const shown = state().accounts
    server.use(
      http.get('/api/v1/admin/accounts', () =>
        HttpResponse.json({ error: 'Datenbank nicht erreichbar' }, { status: 503 }),
      ),
    )

    await state().loadAccounts()

    expect(state().error).toBe('Datenbank nicht erreichbar')
    expect(state().accounts).toEqual(shown)
  })

  it('leaves the list untouched when the review counts cannot be loaded', async () => {
    await state().loadAccounts()
    server.use(
      http.get('/api/v1/admin/local-users/summary', () =>
        HttpResponse.json({ error: 'kaputt' }, { status: 500 }),
      ),
    )

    await state().loadSummary()

    expect(state().summary).toBeNull()
    expect(state().error).toBeNull()
    expect(state().accounts.length).toBeGreaterThan(0)
  })

  /**
   * Das Testdoppel prüft **alle fünf** Zahlengrenzen aus ADR-0033 (Review-Runde 1, HIGH 1) — sonst
   * hätte ein Feld, dessen Grenze nur das Backend kennt, in den Tests nie eine Ablehnung erzeugt.
   */
  it.each([
    ['passwordMinLength', 7],
    ['invitationTokenTtlHours', 0],
    ['resetTokenTtlMinutes', 5000],
    ['defaultExpiryDays', 0],
    ['inactiveDays', 29],
  ] as const)('refuses %s out of range with a field error', async (field, value) => {
    await state().loadSettings()
    const settings = state().settings!
    const request = {
      enabled: settings.enabled,
      selfRegistrationEnabled: settings.selfRegistrationEnabled,
      selfRegistrationAllowedDomains: settings.selfRegistrationAllowedDomains,
      passwordResetEnabled: settings.passwordResetEnabled,
      passwordMinLength: settings.passwordMinLength,
      invitationTokenTtlHours: settings.invitationTokenTtlHours,
      resetTokenTtlMinutes: settings.resetTokenTtlMinutes,
      defaultExpiryDays: settings.defaultExpiryDays,
      inactiveDays: settings.inactiveDays,
      [field]: value,
    }

    await expect(state().saveSettings(request)).rejects.toSatisfy((err: unknown) =>
      apiFieldErrors(err).some((violation) => violation.field === field),
    )
  })

  it('refuses an empty number field the way an empty input would send it', async () => {
    await state().loadSettings()
    const settings = state().settings!
    await expect(
      state().saveSettings({ ...settings, passwordMinLength: Number.NaN }),
    ).rejects.toSatisfy((err: unknown) =>
      apiFieldErrors(err).some((violation) => violation.field === 'passwordMinLength'),
    )
  })

  it('names the number of ended sessions when the local sign-in is switched off', async () => {
    await state().loadSettings()
    const settings = state().settings!

    const saved = await state().saveSettings({
      enabled: false,
      selfRegistrationEnabled: settings.selfRegistrationEnabled,
      selfRegistrationAllowedDomains: settings.selfRegistrationAllowedDomains,
      passwordResetEnabled: settings.passwordResetEnabled,
      passwordMinLength: settings.passwordMinLength,
      invitationTokenTtlHours: settings.invitationTokenTtlHours,
      resetTokenTtlMinutes: settings.resetTokenTtlMinutes,
      defaultExpiryDays: settings.defaultExpiryDays,
      inactiveDays: settings.inactiveDays,
    })

    expect(saved.enabled).toBe(false)
    expect(saved.revokedSessions).toBeGreaterThan(0)
    expect(state().settings?.enabled).toBe(false)

    // Die Zahl gehört nur in die Antwort des abschaltenden PUT: Ein GET danach darf sie nicht
    // führen (Review-Runde 1, LOW 11).
    await state().loadSettings()
    expect(state().settings?.revokedSessions ?? null).toBeNull()
  })

  it('narrows the list by origin and by a single provider', async () => {
    await state().setFilters({ providerType: 'LOCAL' })
    expect(state().accounts.every((account) => account.providerType === 'LOCAL')).toBe(true)
    expect(state().total).toBe(mockLocalUsers.length)

    await state().setFilters({ providerType: 'OIDC', providerId: PARTNER_PROVIDER.id })
    expect(state().accounts.map((account) => account.displayName)).toEqual(['P. Admin (Partner)'])
    expect(state().accounts[0].roleManagedByProvider).toBe(true)

    // the local-only filters never match a provider account, whatever the origin says
    await state().setFilters({ providerType: 'OIDC', providerId: null, status: 'ACTIVE' })
    expect(state().total).toBe(0)
  })

  it('changes a role over the one role endpoint and patches the row of either origin', async () => {
    await state().loadAccounts()

    const provider = await state().changeRole('oidc-user-weber', 'AUDITOR')
    expect(provider.systemRole).toBe('AUDITOR')
    expect(state().accounts.find((account) => account.id === 'oidc-user-weber')?.systemRole).toBe(
      'AUDITOR',
    )

    await state().changeRole('local-user-klein', 'AUDITOR')
    const klein = state().accounts.find((account) => account.id === 'local-user-klein')
    expect(klein?.systemRole).toBe('AUDITOR')
    expect(klein?.local?.systemRole).toBe('AUDITOR')
  })

  it('keeps the refusals of the role endpoint reachable for the curated message', async () => {
    await state().loadAccounts()
    await expect(state().changeRole(ROLE_MANAGED_USER_ID, 'USER')).rejects.toThrow(/Partnerportal/)
    await expect(state().changeRole(LAST_ADMIN_USER_ID, 'USER')).rejects.toSatisfy(
      (err: unknown) => apiErrorCode(err) === 'LAST_LOGIN_CAPABLE_ADMIN',
    )
  })
})
