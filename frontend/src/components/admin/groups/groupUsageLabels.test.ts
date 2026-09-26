import { describe, expect, it } from 'vitest'
import type { GroupEffectsResponse } from '../../../types/api'
import { GROUP_UNUSED, groupUsageDetails, groupUsageShort } from './groupUsageLabels'

function effects(overrides: Partial<GroupEffectsResponse>): GroupEffectsResponse {
  return {
    groupId: 'g1',
    name: 'Gruppe',
    origin: 'INTERNAL',
    dissolved: false,
    protectedGroup: false,
    assetGrants: 0,
    grantedAssets: 0,
    spaceMemberships: 0,
    spaces: 0,
    ownedAssets: 0,
    capabilities: 0,
    scopedAuthorizations: 0,
    summary: '',
    ...overrides,
  } as GroupEffectsResponse
}

describe('groupUsageLabels', () => {
  it('names an unused group as such', () => {
    expect(groupUsageShort(effects({}))).toBe(GROUP_UNUSED)
    expect(groupUsageDetails(effects({}))).toEqual([])
  })

  it('counts libraries and spaces, not grants and memberships', () => {
    const usage = effects({ assetGrants: 3, grantedAssets: 2, spaceMemberships: 2, spaces: 1 })

    expect(groupUsageShort(usage)).toBe('2 Bibliotheken · 1 Space')
    expect(groupUsageDetails(usage)).toEqual(['Rechte an 2 Bibliotheken', 'Mitglied in 1 Space'])
  })

  it('spells out ownership, create rights and diagnostic authorisations', () => {
    const usage = effects({ ownedAssets: 1, capabilities: 2, scopedAuthorizations: 1 })

    expect(groupUsageShort(usage)).toBe('Eigentum · Anlegerechte · Diagnose-Vollmacht')
    expect(groupUsageDetails(usage)).toEqual([
      'Eigentümerin von 1 Bibliothek',
      '2 Anlegerechte, zum Beispiel für Bibliotheken oder Gruppen',
      'Geltungsbereich von 1 Diagnose-Vollmacht',
    ])
  })
})
