import { describe, expect, test } from 'vitest'
import type { CapabilityGrantResponse, CapabilityOverviewResponse } from '../../../types/api'
import {
  accessBadgeLabel,
  bucketByAccess,
  currentAccess,
  objectPhrase,
  planAccessChange,
  resultSentence,
  scopeShortLabel,
} from './capabilityAccess'

function grant(
  id: string,
  subjectType: CapabilityGrantResponse['subjectType'],
  subjectId: string | null = null,
  subjectName: string | null = null,
): CapabilityGrantResponse {
  return {
    id,
    capability: 'CREATE_SPACE',
    subjectType,
    subjectId,
    subjectName,
    grantedByUserId: null,
    createdAt: '2026-10-01T08:00:00Z',
  }
}

function entry(
  grants: CapabilityGrantResponse[],
  extra: Partial<CapabilityOverviewResponse> = {},
): CapabilityOverviewResponse {
  return {
    capability: 'CREATE_SPACE',
    label: 'Spaces anlegen',
    statement: 'Stand',
    grants,
    ...extra,
  }
}

const referat = { type: 'GROUP' as const, id: 'g-32', name: 'Referat 32 Ordnung' }
const ines = { type: 'USER' as const, id: 'u-ines', name: 'Ines Vogel' }

describe('currentAccess', () => {
  test('reads all accounts, named subjects and the system-administration-only state', () => {
    expect(currentAccess(entry([grant('a', 'ALL_ACCOUNTS')])).level).toBe('ALL')
    expect(currentAccess(entry([])).level).toBe('ADMIN_ONLY')
    const selected = currentAccess(entry([grant('b', 'GROUP', 'g-32', 'Referat 32 Ordnung')]))
    expect(selected).toEqual({ level: 'SELECTED', subjects: [referat] })
  })

  test('keeps the named subjects next to all accounts, so a later restriction starts from them', () => {
    const access = currentAccess(
      entry([grant('a', 'ALL_ACCOUNTS'), grant('b', 'USER', 'u-ines', 'Ines Vogel')]),
    )
    expect(access).toEqual({ level: 'ALL', subjects: [ines] })
  })
})

describe('planAccessChange', () => {
  test('restricting from all accounts grants first and withdraws all accounts last', () => {
    const plan = planAccessChange(entry([grant('all', 'ALL_ACCOUNTS')]), {
      level: 'SELECTED',
      subjects: [referat, ines],
    })
    expect(plan.steps).toEqual([
      { kind: 'GRANT', subjectType: 'GROUP', subjectId: 'g-32', name: 'Referat 32 Ordnung' },
      { kind: 'GRANT', subjectType: 'USER', subjectId: 'u-ines', name: 'Ines Vogel' },
      { kind: 'REVOKE', grantId: 'all', subjectType: 'ALL_ACCOUNTS', name: 'Alle Konten' },
    ])
    expect(plan.withdrawsAllAccounts).toBe(true)
  })

  test('replacing one named subject by another adds before it removes', () => {
    const plan = planAccessChange(entry([grant('old', 'GROUP', 'g-32', 'Referat 32 Ordnung')]), {
      level: 'SELECTED',
      subjects: [ines],
    })
    expect(plan.steps.map((step) => step.kind)).toEqual(['GRANT', 'REVOKE'])
    expect(plan.withdrawsAllAccounts).toBe(false)
  })

  test('opening to all accounts keeps the named subjects', () => {
    const plan = planAccessChange(entry([grant('g', 'GROUP', 'g-32', 'Referat 32 Ordnung')]), {
      level: 'ALL',
      subjects: [referat],
    })
    expect(plan.steps).toEqual([
      { kind: 'GRANT', subjectType: 'ALL_ACCOUNTS', subjectId: null, name: 'Alle Konten' },
    ])
  })

  test('system administration only withdraws every grant, all accounts last', () => {
    const plan = planAccessChange(
      entry([grant('all', 'ALL_ACCOUNTS'), grant('g', 'GROUP', 'g-32', 'Referat 32 Ordnung')]),
      { level: 'ADMIN_ONLY', subjects: [] },
    )
    expect(plan.steps.map((step) => (step.kind === 'REVOKE' ? step.grantId : ''))).toEqual([
      'g',
      'all',
    ])
  })

  test('an unchanged choice plans nothing', () => {
    const current = entry([grant('g', 'GROUP', 'g-32', 'Referat 32 Ordnung')])
    expect(planAccessChange(current, currentAccess(current)).steps).toEqual([])
  })
})

describe('sentences', () => {
  test('name the result in plain words, the system administration always included', () => {
    const phrase = 'Spaces anlegen'
    expect(resultSentence({ level: 'ALL', subjects: [] }, phrase)).toBe(
      'Alle Konten dürfen Spaces anlegen.',
    )
    expect(resultSentence({ level: 'ADMIN_ONLY', subjects: [] }, phrase)).toBe(
      'Nur die Systemverwaltung darf Spaces anlegen.',
    )
    expect(resultSentence({ level: 'SELECTED', subjects: [referat, ines] }, phrase)).toBe(
      'Referat 32 Ordnung, Ines Vogel und die Systemverwaltung dürfen Spaces anlegen.',
    )
  })

  test('count named subjects for the badge', () => {
    expect(accessBadgeLabel({ level: 'SELECTED', subjects: [referat] })).toBe('1 Gruppe')
    const referat50 = { type: 'GROUP' as const, id: 'g-50', name: 'Referat 50' }
    // the same group named twice counts once
    expect(
      accessBadgeLabel({ level: 'SELECTED', subjects: [referat, ines, referat50, referat] }),
    ).toBe('2 Gruppen, 1 Person')
    expect(accessBadgeLabel({ level: 'ALL', subjects: [ines] })).toBe('Alle Konten')
    expect(accessBadgeLabel({ level: 'ADMIN_ONLY', subjects: [] })).toBe('Nur Systemverwaltung')
  })

  test('phrase a scope by its kind', () => {
    const type = entry([], {
      capability: 'CREATE_CONNECTOR_LIBRARY',
      scope: 'TYPE:CONFLUENCE',
      scopeLabel: 'Quellart Confluence',
    })
    const profile = entry([], {
      capability: 'CREATE_CONNECTOR_LIBRARY',
      scope: 'PROFILE:p-1',
      scopeLabel: 'Zugang Nextcloud intern',
    })
    expect(scopeShortLabel(type)).toBe('Confluence')
    expect(objectPhrase(type)).toBe('Bibliotheken mit Inhalten aus Confluence anlegen')
    expect(objectPhrase(profile)).toBe('Bibliotheken über den Zugang „Nextcloud intern“ anlegen')
  })
})

describe('bucketByAccess', () => {
  test('puts scopes with the same state together, in the order of their first appearance', () => {
    const scoped = (scope: string, label: string, grants: CapabilityGrantResponse[]) =>
      entry(grants, { capability: 'CREATE_CONNECTOR_LIBRARY', scope, scopeLabel: label })
    const buckets = bucketByAccess([
      scoped('TYPE:A', 'Quellart A', [grant('1', 'ALL_ACCOUNTS')]),
      scoped('TYPE:B', 'Quellart B', []),
      scoped('TYPE:C', 'Quellart C', [grant('2', 'ALL_ACCOUNTS')]),
    ])
    expect(buckets.map((bucket) => [bucket.label, bucket.entries.map(scopeShortLabel)])).toEqual([
      ['Alle Konten', ['A', 'C']],
      ['Nur Systemverwaltung', ['B']],
    ])
  })
})
