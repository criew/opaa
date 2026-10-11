import { describe, expect, it } from 'vitest'
import type { DirectorySyncStatusResponse, OidcProviderResponse } from '../../../types/api'
import {
  agoLabel,
  attentionOf,
  clockLabel,
  intervalLabel,
  matchesFilter,
  nextRunOf,
  runBlocker,
  switchOnBlocker,
  syncStateOf,
} from './directorySyncState'

const NOW = new Date('2026-09-20T12:00:00Z')

function provider(overrides: Partial<OidcProviderResponse> = {}): OidcProviderResponse {
  return {
    id: 'p-1',
    displayName: 'Verzeichnisdienst',
    enabled: true,
    isDefault: true,
    isExternal: false,
    providerType: 'OIDC',
    directorySyncEnabled: true,
    directorySyncIntervalMinutes: 360,
    sortOrder: 1,
    issuerUri: 'http://localhost:8180/realms/opaa',
    clientId: 'opaa-frontend',
    jwkSetUri: null,
    claimMapping: {
      emailClaim: 'email',
      displayNameClaim: 'name',
      rolesClaim: null,
      systemAdminRole: null,
      auditorRole: null,
      groupsClaim: null,
    },
    directoryConnector: {
      type: 'KEYCLOAK',
      baseUrl: 'http://keycloak:8180',
      realm: 'opaa',
      clientId: 'opaa-directory',
      updatedAt: '2026-09-01T08:00:00Z',
    },
    registryState: 'READY',
    registryMessage: null,
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
    ...overrides,
  }
}

function status(overrides: Partial<DirectorySyncStatusResponse> = {}): DirectorySyncStatusResponse {
  return {
    providerId: 'p-1',
    providerDisplayName: 'Verzeichnisdienst',
    providerEnabled: true,
    enabled: true,
    intervalMinutes: 360,
    lastRunAt: '2026-09-20T09:00:00Z',
    lastOutcome: 'APPLIED',
    lastMessage: 'Synchronisation angewendet.',
    lastAppliedAt: '2026-09-20T09:00:00Z',
    lastChangedFraction: 0.02,
    pendingPlan: null,
    ...overrides,
  }
}

describe('syncStateOf', () => {
  it('names a running sync with its interval', () => {
    expect(syncStateOf(provider())).toMatchObject({ kind: 'ON', label: 'Eingeschaltet' })
  })

  it('calls a switched-on sync of a disabled provider paused', () => {
    expect(syncStateOf(provider({ enabled: false }))).toMatchObject({
      kind: 'PAUSED',
      label: 'Pausiert',
    })
  })

  // ADR-0036, Entscheidung 2: a provider with a groups claim has its one mechanism already.
  it('names the token claim as the mechanism of a provider that carries one', () => {
    const withClaim = provider({
      directorySyncEnabled: false,
      claimMapping: { ...provider().claimMapping, groupsClaim: 'groups' },
    })
    expect(syncStateOf(withClaim)).toMatchObject({ kind: 'TOKEN', label: 'Gruppen aus dem Token' })
  })

  it('calls a switched-off sync off', () => {
    expect(syncStateOf(provider({ directorySyncEnabled: false }))).toMatchObject({
      kind: 'OFF',
      label: 'Aus',
    })
  })
})

describe('attentionOf', () => {
  it('puts a pending plan first, with its age and what it would do', () => {
    const attention = attentionOf(
      provider(),
      status({
        lastOutcome: 'PENDING_CONFIRMATION',
        pendingPlan: {
          id: 'plan-1',
          createdAt: '2026-09-18T12:00:00Z',
          changedFraction: 0.67,
          membershipsRemoved: 23,
          accountsLocked: 1,
        },
      }),
      NOW,
    )
    expect(attention).toMatchObject({ tone: 'warning', label: 'Entscheidung nötig' })
    expect(attention?.detail).toBe(
      'Plan wartet seit 2 Tagen · würde 23 Mitgliedschaften entziehen und 1 Konto sperren',
    )
  })

  it('leaves out an effect the plan does not have', () => {
    const attention = attentionOf(
      provider(),
      status({
        pendingPlan: {
          id: 'plan-1',
          createdAt: '2026-09-20T08:00:00Z',
          changedFraction: 0.4,
          membershipsRemoved: 4,
          accountsLocked: 0,
        },
      }),
      NOW,
    )
    expect(attention?.detail).toBe(
      'Plan wartet seit 4 Stunden · würde 4 Mitgliedschaften entziehen',
    )
  })

  it('reports an unreachable directory as an error', () => {
    expect(attentionOf(provider(), status({ lastOutcome: 'UNREACHABLE' }), NOW)).toMatchObject({
      tone: 'error',
      label: 'Letzter Lauf gescheitert',
    })
  })

  it('reports a switched-on sync without directory access', () => {
    expect(attentionOf(provider({ directoryConnector: null }), status(), NOW)).toMatchObject({
      tone: 'warning',
      label: 'Verzeichniszugang fehlt',
    })
  })

  it('asks nothing of a healthy or switched-off sync', () => {
    expect(attentionOf(provider(), status(), NOW)).toBeNull()
    expect(attentionOf(provider({ directorySyncEnabled: false }), undefined, NOW)).toBeNull()
  })

  // A paused provider runs nothing, so its access is no task while it stays disabled.
  it('asks nothing of a paused provider without access', () => {
    expect(
      attentionOf(provider({ enabled: false, directoryConnector: null }), undefined, NOW),
    ).toBeNull()
  })
})

describe('nextRunOf', () => {
  // DirectorySyncScheduler: due once the last run is at least one interval ago.
  it('is one interval after the last run', () => {
    expect(nextRunOf(provider(), status(), NOW)).toEqual({
      kind: 'AT',
      at: new Date('2026-09-20T15:00:00Z'),
    })
  })

  it('is due now when the interval has passed or the provider never ran', () => {
    expect(nextRunOf(provider(), status({ lastRunAt: '2026-09-19T00:00:00Z' }), NOW)).toEqual({
      kind: 'DUE',
    })
    expect(nextRunOf(provider(), undefined, NOW)).toEqual({ kind: 'DUE' })
  })

  it('has none while the sync is off or paused', () => {
    expect(nextRunOf(provider({ directorySyncEnabled: false }), status(), NOW)).toEqual({
      kind: 'NONE',
    })
    expect(nextRunOf(provider({ enabled: false }), status(), NOW)).toEqual({ kind: 'NONE' })
  })
})

describe('intervalLabel', () => {
  it('reads minutes as the largest whole unit', () => {
    expect(intervalLabel(5)).toBe('alle 5 Minuten')
    expect(intervalLabel(90)).toBe('alle 90 Minuten')
    expect(intervalLabel(60)).toBe('stündlich')
    expect(intervalLabel(360)).toBe('alle 6 Stunden')
    expect(intervalLabel(1440)).toBe('täglich')
    expect(intervalLabel(10080)).toBe('wöchentlich')
    expect(intervalLabel(2880)).toBe('alle 2 Tage')
  })
})

describe('switchOnBlocker and runBlocker', () => {
  it('blocks switching on while a groups claim is set', () => {
    const withClaim = provider({
      directorySyncEnabled: false,
      claimMapping: { ...provider().claimMapping, groupsClaim: 'groups' },
    })
    expect(switchOnBlocker(withClaim)).toMatch(/Gruppen-Claim „groups“/)
    expect(switchOnBlocker(provider({ directorySyncEnabled: false }))).toBeNull()
  })

  it('blocks a manual run while the sync is off or the provider disabled', () => {
    expect(runBlocker(provider({ directorySyncEnabled: false }))).toMatch(/eingeschaltet/)
    expect(runBlocker(provider({ enabled: false }))).toMatch(/deaktiviert/)
    expect(runBlocker(provider())).toBeNull()
  })
})

describe('matchesFilter', () => {
  const entry = { provider: provider(), status: status(), attention: null }

  it('keeps everything under „all“ and filters by name, case-insensitive', () => {
    expect(matchesFilter(entry, 'ALL', '')).toBe(true)
    expect(matchesFilter(entry, 'ALL', 'verzeich')).toBe(true)
    expect(matchesFilter(entry, 'ALL', 'partner')).toBe(false)
  })

  it('keeps only entries with something to do under „attention“', () => {
    expect(matchesFilter(entry, 'ATTENTION', '')).toBe(false)
    expect(
      matchesFilter(
        { ...entry, attention: { tone: 'error', label: 'x', detail: 'y' } },
        'ATTENTION',
        '',
      ),
    ).toBe(true)
  })

  it('keeps only switched-on syncs under „on“', () => {
    expect(matchesFilter(entry, 'ON', '')).toBe(true)
    expect(
      matchesFilter({ ...entry, provider: provider({ directorySyncEnabled: false }) }, 'ON', ''),
    ).toBe(false)
  })
})

describe('clockLabel and agoLabel', () => {
  it('says „heute" for a time later today and the date otherwise', () => {
    const now = new Date(2026, 8, 20, 9, 0)
    expect(clockLabel(new Date(2026, 8, 20, 15, 0), now)).toBe('heute, 15:00')
    expect(clockLabel(new Date(2026, 8, 21, 6, 5), now)).toBe('21.09., 06:05')
  })

  it('reads a past time as its age', () => {
    expect(agoLabel('2026-09-20T09:00:00Z', NOW)).toBe('vor 3 Stunden')
    expect(agoLabel('2026-09-17T12:00:00Z', NOW)).toBe('vor 3 Tagen')
  })
})
