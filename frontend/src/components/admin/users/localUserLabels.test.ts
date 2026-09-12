import { describe, expect, it } from 'vitest'
import { AxiosError } from 'axios'
import type { LocalUserResponse } from '../../../types/api'
import {
  defaultExpiryInputValue,
  formatExpiry,
  fromDateInputValue,
  localAccountStateText,
  localUserErrorMessage,
  shortenReason,
  toDateInputValue,
} from './localUserLabels'

function account(overrides: Partial<LocalUserResponse> = {}): LocalUserResponse {
  return {
    id: 'local-user-1',
    email: 'a@stadt.example',
    displayName: 'A',
    systemRole: 'USER',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    createdReason: 'Grund',
    createdAt: '2026-01-01T00:00:00Z',
    activity: 'ACTIVE',
    bootstrap: false,
    ...overrides,
  }
}

/** An error the way `normalizeError` produces it: German message, AxiosError in `cause`. */
function apiError(message: string, data: unknown, status = 409): Error {
  const axiosError = new AxiosError(message)
  axiosError.response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: { headers: {} as never },
  }
  return new Error(message, { cause: axiosError })
}

describe('localUserLabels', () => {
  it('names the lock reason as part of the state', () => {
    expect(localAccountStateText(account())).toBe('Aktiv')
    expect(localAccountStateText(account({ status: 'INVITED' }))).toBe('Eingeladen')
    expect(localAccountStateText(account({ status: 'EXPIRED' }))).toBe('Abgelaufen')
    expect(localAccountStateText(account({ status: 'LOCKED', lockedReason: 'ADMIN' }))).toBe(
      'Gesperrt (Verwalter)',
    )
    expect(
      localAccountStateText(account({ status: 'LOCKED', lockedReason: 'FAILED_LOGINS' })),
    ).toBe('Gesperrt (Fehlversuche)')
    expect(localAccountStateText(account({ status: 'LOCKED', lockedReason: 'INACTIVITY' }))).toBe(
      'Gesperrt (Inaktivität)',
    )
  })

  it('shows an account without an expiry date as a dash', () => {
    expect(formatExpiry(null)).toBe('—')
    expect(formatExpiry(undefined)).toBe('—')
    expect(formatExpiry('2026-12-31T22:59:59Z')).toMatch(/2026/)
  })

  it('keeps the chosen day when a date travels to the backend and back', () => {
    const iso = fromDateInputValue('2027-03-31')
    expect(iso).not.toBeNull()
    expect(toDateInputValue(iso)).toBe('2027-03-31')
    // End of day, so an expiry date means „bis einschließlich dieses Tages".
    expect(new Date(iso!).getHours()).toBe(23)
    expect(fromDateInputValue('')).toBeNull()
  })

  it('prefills the expiry date with the configured number of days', () => {
    const prefill = defaultExpiryInputValue(30)
    const expected = new Date()
    expected.setDate(expected.getDate() + 30)
    expect(prefill).toBe(toDateInputValue(expected.toISOString()))
  })

  it('shortens a long creation reason and leaves a short one alone', () => {
    expect(shortenReason('kurz')).toBe('kurz')
    const long = 'x'.repeat(80)
    expect(shortenReason(long)).toHaveLength(60)
    expect(shortenReason(long).endsWith('…')).toBe(true)
  })

  it('prefers the curated text of a known conflict code', () => {
    const err = apiError('Backend-Wortlaut', {
      error: 'Backend-Wortlaut',
      code: 'LAST_LOGIN_CAPABLE_ADMIN',
    })
    expect(localUserErrorMessage(err, 'Fehlgeschlagen')).toContain('anmeldefähiger Systemverwalter')
  })

  it('passes the backend message through where no curated text exists', () => {
    const err = apiError('Das Konto wird noch in Nachweisbeständen referenziert', {
      error: 'Das Konto wird noch in Nachweisbeständen referenziert',
      code: 'ACCOUNT_OWNS_CONTENT',
    })
    expect(localUserErrorMessage(err, 'Fehlgeschlagen')).toBe(
      'Das Konto wird noch in Nachweisbeständen referenziert',
    )
  })

  it('falls back to its own sentence for an error without a message', () => {
    expect(localUserErrorMessage(new Error(''), 'Fehlgeschlagen')).toBe('Fehlgeschlagen')
    expect(localUserErrorMessage('kein Error', 'Fehlgeschlagen')).toBe('Fehlgeschlagen')
  })
})
