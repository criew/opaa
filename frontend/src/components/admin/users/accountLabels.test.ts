import { describe, expect, it } from 'vitest'
import type { AccountResponse } from '../../../types/api'
import { providerStateHint, providerStateText } from './accountLabels'

function providerAccount(overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id: 'account-1',
    email: 'a@stadt.example',
    displayName: 'A',
    systemRole: 'USER',
    providerType: 'OIDC',
    issuer: 'https://idp.example/realms/haus',
    roleManagedByProvider: false,
    createdAt: '2026-01-01T00:00:00Z',
    provider: { id: 'provider-1', displayName: 'Haus A', enabled: true },
    ...overrides,
  }
}

/**
 * The state cell of an identity-provider account (#1818). The lock from the directory is a state
 * OPAA itself holds and outranks both provider states: it is what stops this account right now,
 * whatever the provider row says.
 */
describe('providerStateText', () => {
  it('nennt den Anbieter, solange nichts dazwischenkommt', () => {
    expect(providerStateText(providerAccount())).toBe('Beim Anbieter')
    expect(providerStateHint(providerAccount())).toBeNull()
  })

  it('geht der Meldung „Anbieter deaktiviert" vor', () => {
    const account = providerAccount({
      directoryLocked: true,
      provider: { id: 'provider-1', displayName: 'Haus A', enabled: false },
    })

    expect(providerStateText(account)).toBe('Im Verzeichnis gesperrt')
    expect(providerStateHint(account)).toMatch(/Das Verzeichnis meldet dieses Konto/)
  })

  it('geht der Meldung „Anmeldung nicht möglich" eines gelöschten Anbieters vor', () => {
    const account = providerAccount({ directoryLocked: true, provider: undefined })

    expect(providerStateText(account)).toBe('Im Verzeichnis gesperrt')
    expect(providerStateHint(account)).toMatch(/Aufgehoben wird die Sperre im Verzeichnis/)
  })

  it('lässt die beiden bestehenden Zustände unverändert', () => {
    expect(
      providerStateText(
        providerAccount({ provider: { id: 'provider-1', displayName: 'Haus A', enabled: false } }),
      ),
    ).toBe('Anbieter deaktiviert')
    expect(providerStateText(providerAccount({ provider: undefined }))).toBe(
      'Anmeldung nicht möglich',
    )
  })
})
