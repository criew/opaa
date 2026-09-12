import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import {
  LAST_ADMIN_USER_ID,
  MAIL_FAILING_ADDRESS_SUFFIX,
  OWNS_CONTENT_USER_ID,
  mockLocalUsers,
} from '../mocks/localUserFixtures'
import { apiErrorCode, apiFieldErrors } from '../services/apiErrorDetails'
import { INITIAL_LOCAL_USER_FILTERS, useUserAdminStore } from './userAdminStore'

function state() {
  return useUserAdminStore.getState()
}

describe('userAdminStore', () => {
  beforeEach(() => {
    state().reset()
  })

  it('loads the first page of local accounts with the default sort', async () => {
    await state().loadUsers()
    expect(state().filters).toEqual(INITIAL_LOCAL_USER_FILTERS)
    expect(state().total).toBe(mockLocalUsers.length)
    // displayName ascending: „A. Vogt" first, „T. Klein" last of the seven
    expect(state().users[0].displayName).toBe('A. Vogt')
    expect(state().error).toBeNull()
  })

  it('passes the review filters to the API and returns to the first page', async () => {
    await state().setFilters({ page: 1 })
    expect(state().filters.page).toBe(1)

    await state().setFilters({ review: 'WITHOUT_EXPIRY' })
    expect(state().filters.page).toBe(0)
    expect(state().users.every((user) => !user.expiresAt)).toBe(true)

    await state().setFilters({ review: 'ALL', status: 'INVITED' })
    expect(state().users.map((user) => user.displayName)).toEqual(['T. Klein'])
  })

  it('sorts only by the four allow-listed fields', async () => {
    await state().setFilters({ sort: 'email', direction: 'desc' })
    const emails = state().users.map((user) => user.email)
    expect(emails).toEqual([...emails].sort().reverse())
  })

  it('adopts a locked account from its own response and refreshes the review counts', async () => {
    await state().loadUsers()
    await state().loadSummary()
    const lockedBefore = state().summary?.locked ?? 0

    const updated = await state().lockUser('local-user-klein')

    expect(updated.status).toBe('LOCKED')
    expect(state().users.find((user) => user.id === 'local-user-klein')?.status).toBe('LOCKED')
    expect(state().summary?.locked).toBe(lockedBefore + 1)
  })

  it('reloads the page after creating an account and carries the one-time secret through', async () => {
    await state().loadUsers()
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
    await state().loadUsers()
    await expect(state().deleteUser(OWNS_CONTENT_USER_ID)).rejects.toSatisfy(
      (err: unknown) => apiErrorCode(err) === 'ACCOUNT_OWNS_CONTENT',
    )
    await expect(state().lockUser(LAST_ADMIN_USER_ID)).rejects.toSatisfy(
      (err: unknown) => apiErrorCode(err) === 'LAST_LOGIN_CAPABLE_ADMIN',
    )
  })

  it('reports a failed list load without emptying what is already shown', async () => {
    await state().loadUsers()
    const shown = state().users
    server.use(
      http.get('/api/v1/admin/local-users', () =>
        HttpResponse.json({ error: 'Datenbank nicht erreichbar' }, { status: 503 }),
      ),
    )

    await state().loadUsers()

    expect(state().error).toBe('Datenbank nicht erreichbar')
    expect(state().users).toEqual(shown)
  })

  it('leaves the list untouched when the review counts cannot be loaded', async () => {
    await state().loadUsers()
    server.use(
      http.get('/api/v1/admin/local-users/summary', () =>
        HttpResponse.json({ error: 'kaputt' }, { status: 500 }),
      ),
    )

    await state().loadSummary()

    expect(state().summary).toBeNull()
    expect(state().error).toBeNull()
    expect(state().users.length).toBeGreaterThan(0)
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
})
