import { describe, expect, it } from 'vitest'
import {
  mockConnectionProfiles,
  resetMockConnectionProfiles,
} from '../../../mocks/connectionProfileFixtures'
import type { ConnectionProfileResponse } from '@/shared/api/types'
import type { Access } from '@/features/capability'
import { attentionOf, hostOf, profileAccessEntry, summaryOf } from './profileState'

function profile(change: Partial<ConnectionProfileResponse> = {}): ConnectionProfileResponse {
  resetMockConnectionProfiles()
  return { ...mockConnectionProfiles[0], ...change }
}

const RELEASED: Access = { level: 'ALL', subjects: [] }
const NOBODY: Access = { level: 'ADMIN_ONLY', subjects: [] }
const TODAY = '2026-10-11'

describe('attentionOf', () => {
  it('names the most pressing task first', () => {
    const all = profile({
      signInRejected: true,
      clientSecretExpiresSoon: true,
      clientSecretExpiresOn: '2026-10-01',
      expiredConnectionWarning: true,
    })
    expect(attentionOf(all, NOBODY, TODAY)?.kind).toBe('SIGN_IN_REJECTED')
    expect(attentionOf({ ...all, signInRejected: false }, NOBODY, TODAY)?.kind).toBe(
      'SECRET_EXPIRED',
    )
    expect(
      attentionOf(
        { ...all, signInRejected: false, clientSecretExpiresOn: '2026-10-20' },
        NOBODY,
        TODAY,
      ),
    ).toMatchObject({ kind: 'SECRET_EXPIRING', label: 'Secret läuft ab am 20.10.2026' })
    expect(
      attentionOf({ ...all, signInRejected: false, clientSecretExpiresSoon: false }, NOBODY, TODAY)
        ?.kind,
    ).toBe('MANY_EXPIRED')
    expect(attentionOf(profile(), NOBODY, TODAY)?.kind).toBe('NOT_RELEASED')
  })

  it('has nothing to do for a released profile, and no verdict on an unknown release', () => {
    expect(attentionOf(profile(), RELEASED, TODAY)).toBeNull()
    expect(attentionOf(profile(), null, TODAY)).toBeNull()
  })

  it('takes a lock for a state, not for a task', () => {
    expect(attentionOf(profile({ locked: true }), RELEASED, TODAY)).toBeNull()
  })
})

describe('summaryOf', () => {
  it('counts the profiles and the resting sources, an error tone wins', () => {
    const rejected = attentionOf(profile({ signInRejected: true }), RELEASED, TODAY)
    const nobody = attentionOf(profile(), NOBODY, TODAY)
    expect(summaryOf([rejected, nobody, null], 1)).toEqual({
      tone: 'error',
      headline: '2 Zugänge brauchen Aufmerksamkeit; 1 Bibliothek mit ruhender Quelle',
      detail: expect.any(String),
    })
    expect(summaryOf([null], 0)).toMatchObject({
      tone: 'success',
      headline: 'Kein Handlungsbedarf',
    })
  })
})

describe('hostOf', () => {
  it('shows the host and keeps an address without one as it is', () => {
    expect(hostOf('https://cloud.rheinfurt.example/remote.php')).toBe('cloud.rheinfurt.example')
    expect(hostOf('smb://fs01/ablage')).toBe('fs01')
    expect(hostOf('kein url')).toBe('kein url')
    expect(hostOf(null)).toBe('—')
  })
})

describe('profileAccessEntry', () => {
  it('stands in with no grant for a profile the overview does not list yet', () => {
    const entry = profileAccessEntry([], profile({ id: 'new', name: 'Zugang neu' }))
    expect(entry).toMatchObject({
      scope: 'PROFILE:new',
      scopeLabel: 'Zugang Zugang neu',
      grants: [],
    })
  })
})
