import { describe, expect, it } from 'vitest'
import type { GroupDeactivationPreviewResponse } from '@/shared/api/types'
import { groupNotSelectableReason } from '@/components/permissions/subjectSelection'
import { deactivationConsequence } from './groupDeactivation'

const preview: GroupDeactivationPreviewResponse = {
  groupId: 'group-1',
  deactivated: false,
  assetGrants: 1,
  grantedAssets: 1,
  spaceMemberships: 2,
  spaces: 2,
  capabilities: 1,
  activeMembers: 1,
  blockedReason: null,
}

describe('deactivationConsequence', () => {
  it('counts every effect that rests while the group is deactivated', () => {
    expect(deactivationConsequence(preview, true)).toContain(
      '1 Freigabe an 1 Objekt, 2 Space-Mitgliedschaften in 2 Spaces und 1 Anlegerecht ruhen für 1 aktives Konto.',
    )
  })

  it('says so when the group carries nothing', () => {
    const empty = { ...preview, assetGrants: 0, spaceMemberships: 0, capabilities: 0 }

    expect(deactivationConsequence(empty, true)).toContain(
      'Die Gruppe trägt zurzeit keine Freigaben, Space-Mitgliedschaften oder Anlegerechte.',
    )
  })

  it('names what takes effect again on reactivation', () => {
    expect(deactivationConsequence({ ...preview, activeMembers: 4 }, false)).toBe(
      'Die Gruppe wirkt sofort wieder: 1 Freigabe an 1 Objekt, 2 Space-Mitgliedschaften in 2 Spaces und 1 Anlegerecht gelten wieder für 4 aktive Konten.',
    )
  })
})

describe('groupNotSelectableReason', () => {
  it('names a deactivation as the reason a group is not selectable', () => {
    expect(
      groupNotSelectableReason({
        selectable: false,
        dissolved: false,
        providerDisabled: false,
        unmaintained: false,
        deactivated: true,
      }),
    ).toBe('Gruppe deaktiviert — bestehende Rechte ruhen')
  })
})
