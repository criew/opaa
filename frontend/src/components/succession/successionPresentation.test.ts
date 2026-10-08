import { describe, expect, test } from 'vitest'
import type { SuccessionEntryResponse } from '../../types/api'
import { addresseeOf, nextStepOf, problemOf, sinceLabel } from './successionPresentation'

function entry(overrides: Partial<SuccessionEntryResponse>): SuccessionEntryResponse {
  return {
    caseId: 'case-1',
    objectType: 'ASSET',
    assetType: 'KNOWLEDGE_LIBRARY',
    objectId: 'lib-1',
    objectName: 'Ablage Probe',
    addressee: 'SYSTEM_ADMINISTRATION',
    addresseeLabel: 'die Systemverwaltung',
    ownerHint: null,
    membershipHints: [],
    affectedObjects: 0,
    firstSeenAt: null,
    highlighted: false,
    lastReviewedAt: null,
    lastReviewReason: null,
    ...overrides,
  }
}

describe('problemOf', () => {
  test('names the problem of each kind of entry in one plain sentence', () => {
    expect(problemOf('OPEN_SUCCESSION', entry({}))).toBe(
      'Das Konto, dem die Bibliothek gehört, ist nicht mehr aktiv.',
    )
    expect(
      problemOf('OPEN_SUCCESSION', entry({ objectType: 'SPACE', addressee: 'SPACE_ADMINS' })),
    ).toBe('Das Konto, dem der Space gehört, ist nicht mehr aktiv.')
    expect(problemOf('OPEN_SUCCESSION', entry({ objectType: 'GROUP', assetType: null }))).toBe(
      'Die Gruppe hat keine aktiven Verantwortlichen mehr.',
    )
    expect(problemOf('OPEN_SUCCESSION', entry({ addressee: 'GROUP_STEWARDS' }))).toBe(
      'Die Gruppe, der die Bibliothek gehört, hat keine aktiven Mitglieder mehr.',
    )
    expect(
      problemOf(
        'GRANTS_WITHOUT_RECIPIENT',
        entry({ objectType: 'GROUP', assetType: null, affectedObjects: 7 }),
      ),
    ).toBe('Die Gruppe hat Rechte an 7 Objekten, aber kein aktives Mitglied mehr.')
    expect(problemOf('GROUP_WITHOUT_EFFECT', entry({ objectType: 'GROUP', assetType: null }))).toBe(
      'Die Gruppe hat weder aktive Mitglieder noch Rechte.',
    )
  })

  test('uses the singular for one object', () => {
    expect(
      problemOf(
        'GRANTS_WITHOUT_RECIPIENT',
        entry({ objectType: 'GROUP', assetType: null, affectedObjects: 1 }),
      ),
    ).toBe('Die Gruppe hat Rechte an 1 Objekt, aber kein aktives Mitglied mehr.')
  })
})

describe('nextStepOf', () => {
  test('offers exactly one next step per kind of entry', () => {
    expect(nextStepOf('OPEN_SUCCESSION', entry({}))).toBe('HANDOVER')
    expect(nextStepOf('OPEN_SUCCESSION', entry({ objectType: 'SPACE' }))).toBe('HANDOVER')
    expect(nextStepOf('OPEN_SUCCESSION', entry({ addressee: 'GROUP_STEWARDS' }))).toBe(
      'GROUP_ADMIN',
    )
    expect(nextStepOf('OPEN_SUCCESSION', entry({ objectType: 'GROUP' }))).toBe('GROUP_TRANSFER')
    expect(nextStepOf('GRANTS_WITHOUT_RECIPIENT', entry({ objectType: 'GROUP' }))).toBe(
      'GROUP_TRANSFER',
    )
    // a group that holds nothing has nothing to hand over - it is dissolved, not transferred
    expect(nextStepOf('GROUP_WITHOUT_EFFECT', entry({ objectType: 'GROUP' }))).toBe('GROUP_ADMIN')
  })
})

describe('sinceLabel', () => {
  test('says how long an entry has been open, or that it was just found', () => {
    const now = new Date('2026-10-08T12:00:00Z')
    expect(sinceLabel(entry({ firstSeenAt: '2026-09-30T12:00:00Z' }), now)).toBe(
      'offen seit 8 Tagen',
    )
    expect(sinceLabel(entry({ firstSeenAt: null }), now)).toBe('gerade erkannt')
  })
})

describe('addresseeOf', () => {
  test('names who should act without the technical wording of the backend', () => {
    expect(addresseeOf(entry({ addressee: 'SPACE_ADMINS' }))).toBe(
      'die übrigen Administratorinnen und Administratoren des Space',
    )
    expect(addresseeOf(entry({ addressee: 'GROUP_STEWARDS' }))).toBe(
      'die Verantwortlichen der Gruppe',
    )
  })
})
