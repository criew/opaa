import { afterEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '@/shared/session/authStore'
import { notificationTarget } from './notificationTarget'

describe('notificationTarget', () => {
  it.each([
    'SOURCE_CONNECTION_EXPIRING',
    'SOURCE_CONNECTION_EXPIRED',
    'SOURCE_CONNECTION_ENDED',
  ] as const)('leads %s to the source of its library', (type) => {
    expect(notificationTarget({ type, objectId: 'lib-1' })).toBe('/libraries/lib-1?tab=quelle')
  })

  it('leads nowhere for a source connection notification without its library', () => {
    expect(notificationTarget({ type: 'SOURCE_CONNECTION_EXPIRED', objectId: null })).toBeNull()
  })

  it('keeps the targets of the other types', () => {
    expect(notificationTarget({ type: 'CONNECTION_EXPIRED', objectId: 'x' })).toBe(
      '/settings/accounts',
    )
    expect(notificationTarget({ type: 'GROUP_MEMBER_ADDED', objectId: 'x' })).toBeNull()
  })

  it('keeps the start of a self-disclosure a plain entry without a way to the download', () => {
    expect(notificationTarget({ type: 'SELF_DISCLOSURE_STARTED', objectId: null })).toBeNull()
  })

  it('leads an AUDITOR request to the decision of the revision, a grant nowhere', () => {
    expect(notificationTarget({ type: 'AUDITOR_ROLE_REQUESTED', objectId: 'user-1' })).toBe(
      '/revision/rollenantraege',
    )
    expect(notificationTarget({ type: 'AUDITOR_ROLE_GRANTED', objectId: 'user-1' })).toBeNull()
    expect(notificationTarget({ type: 'AUDITOR_ROLE_REVOKED', objectId: 'user-1' })).toBeNull()
    expect(notificationTarget({ type: 'MAIL_CONFIGURATION_CHANGED', objectId: null })).toBeNull()
    expect(
      notificationTarget({ type: 'IDENTITY_PROVIDER_TRUST_CHANGED', objectId: 'provider-1' }),
    ).toBeNull()
  })

  it('leads an expiring secret to its profile in the connections area', () => {
    expect(
      notificationTarget({ type: 'CONNECTION_PROFILE_SECRET_EXPIRING', objectId: 'profile-1' }),
    ).toBe('/admin/connections/access?profile=profile-1')
    expect(notificationTarget({ type: 'CONNECTION_PROFILE_SECRET_EXPIRING', objectId: null })).toBe(
      '/admin/connections/access',
    )
  })

  it('leads the mass retrieval alert to the token list, not to a person', () => {
    expect(
      notificationTarget({ type: 'EXTERNAL_ACCESS_MASS_RETRIEVAL', objectId: 'token-1' }),
    ).toBe('/admin/connections/external-tools#tokens')
  })

  describe('with the external access switched off', () => {
    afterEach(() => {
      useAuthStore.setState({ features: {} })
    })

    it('keeps an earlier mass retrieval alert a plain entry', () => {
      useAuthStore.setState({ features: { externalAccess: false } })

      expect(
        notificationTarget({ type: 'EXTERNAL_ACCESS_MASS_RETRIEVAL', objectId: 'token-1' }),
      ).toBeNull()
    })
  })
})
