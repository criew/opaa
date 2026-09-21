import { describe, expect, it } from 'vitest'
import type { GroupListResponse } from '../../types/api'
import { ageLabel, groupIneffectiveReason, groupOriginLabel } from './groupOriginLabels'

const providerGroup: GroupListResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  description: null,
  kind: 'ORG_UNIT',
  externalId: 'ext-1',
  origin: 'PROVIDER',
  provider: {
    id: 'provider-1',
    displayName: 'Verzeichnis Haus A',
    external: true,
    enabled: true,
    groupMechanism: 'DIRECTORY',
  },
  sourcePath: '/Haus/Referat 50',
  parentGroupId: null,
  memberCount: 23,
  dissolved: false,
  releasedForUse: true,
  protectedGroup: false,
  stewards: [],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

describe('groupOriginLabels', () => {
  it('names the provider of a provider group and "intern" for an internal one', () => {
    expect(groupOriginLabel(providerGroup)).toBe('Verzeichnis Haus A')
    expect(groupOriginLabel({ origin: 'INTERNAL' })).toBe('intern')
  })

  // ADR-0036, Entscheidung 2: Eine aufgelöste Gruppe und die eines deaktivierten Anbieters sind
  // keine wirksamen Gruppen - bestehende Berechtigungen bleiben aber.
  it('names why a group can no longer be chosen', () => {
    expect(groupIneffectiveReason(providerGroup)).toBeNull()
    expect(groupIneffectiveReason({ ...providerGroup, dissolved: true })).toMatch(/Aufgelöst/)
    expect(
      groupIneffectiveReason({
        ...providerGroup,
        provider: { ...providerGroup.provider!, enabled: false },
      }),
    ).toMatch(/deaktiviert/)
  })

  it('reads an age in hours below two days and in days above', () => {
    const now = new Date('2026-09-21T12:00:00Z')
    expect(ageLabel('2026-09-21T11:30:00Z', now)).toBe('unter einer Stunde')
    expect(ageLabel('2026-09-21T09:00:00Z', now)).toBe('3 Stunden')
    expect(ageLabel('2026-09-18T12:00:00Z', now)).toBe('3 Tage')
  })
})
