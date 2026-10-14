import { describe, expect, it } from 'vitest'
import {
  assetReachLabel,
  spaceMembershipLabel,
  assetRoleDescription,
  groupMemberCountLabel,
} from './assetLabels'

describe('assetReachLabel (#1931)', () => {
  it('lets "Alle" outrank every count', () => {
    expect(assetReachLabel({ allAccounts: true, groupCount: 3, userCount: 9 })).toBe('Alle')
  })

  it('reads "nur Sie" for an asset only its owner reaches', () => {
    expect(assetReachLabel({ allAccounts: false, groupCount: 0, userCount: 1 })).toBe('nur Sie')
  })

  it('counts every person with a grant, the reader included', () => {
    expect(assetReachLabel({ allAccounts: false, groupCount: 2, userCount: 4 })).toBe(
      '2 Gruppen, 4 Personen',
    )
    expect(assetReachLabel({ allAccounts: false, groupCount: 1, userCount: 2 })).toBe(
      '1 Gruppe, 2 Personen',
    )
  })

  /**
   * "nur Sie" is the reader's own perspective and holds for exactly one case: a single person has
   * a grant, and that is the reader. One person beside a group is no longer that case.
   */
  it('does not read "nur Sie" as soon as a group reaches the asset too', () => {
    expect(assetReachLabel({ allAccounts: false, groupCount: 1, userCount: 1 })).toBe(
      '1 Gruppe, 1 Person',
    )
  })
})

describe('spaceMembershipLabel (#1970)', () => {
  it('reads "nur Sie" when the caller is the only member, default space or not', () => {
    expect(spaceMembershipLabel({ groupCount: 0, userCount: 1 })).toBe('nur Sie')
  })

  it('counts group and person rows without resolving groups to persons', () => {
    expect(spaceMembershipLabel({ groupCount: 2, userCount: 3 })).toBe('2 Gruppen, 3 Personen')
    expect(spaceMembershipLabel({ groupCount: 1, userCount: 0 })).toBe('1 Gruppe')
    expect(spaceMembershipLabel({ groupCount: 1, userCount: 1 })).toBe('1 Gruppe, 1 Person')
  })
})

describe('assetRoleDescription', () => {
  it('describes a role in the words of the asset type', () => {
    expect(assetRoleDescription('EDITOR', 'KNOWLEDGE_LIBRARY')).toBe(
      'Darf zusätzlich Dokumente ändern, hochladen und entfernen.',
    )
    expect(assetRoleDescription('EDITOR', 'PROMPT_LIBRARY')).toBe(
      'Darf zusätzlich Prompts anlegen, ändern und löschen.',
    )
  })
})

/** #2134: die heutige Größe einer Gruppe an Freigabe und Mitgliedschaft, ohne Mindestgröße. */
describe('groupMemberCountLabel', () => {
  it('nennt jede Zahl, auch unterhalb der Mindestgruppengröße', () => {
    expect(groupMemberCountLabel({ activeMemberCount: 41 })).toBe('41 Mitglieder')
    expect(groupMemberCountLabel({ activeMemberCount: 2 })).toBe('2 Mitglieder')
    expect(groupMemberCountLabel({ activeMemberCount: 1 })).toBe('1 Mitglied')
    expect(groupMemberCountLabel({ activeMemberCount: 0 })).toBe('erreicht derzeit niemanden')
  })

  it('nennt bei einer geschützten Gruppe nichts', () => {
    expect(groupMemberCountLabel({ activeMemberCount: 7, protectedGroup: true })).toBeNull()
    expect(groupMemberCountLabel({ activeMemberCount: null })).toBeNull()
  })
})
