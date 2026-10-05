import { describe, expect, it } from 'vitest'
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
})
