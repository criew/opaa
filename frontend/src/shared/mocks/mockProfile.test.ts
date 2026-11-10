import { afterEach, describe, expect, it } from 'vitest'
import { currentMockProfile, mockProfileUser } from './mockProfile'
import { mockUser } from './authFixtures'

describe('currentMockProfile', () => {
  afterEach(() => {
    window.sessionStorage.removeItem('opaa.mockProfile')
    window.history.replaceState(null, '', '/')
  })

  it('is the system administrator without a choice', () => {
    expect(currentMockProfile()).toBe('admin')
  })

  it('takes the profile from the query and remembers it for the tab', () => {
    window.history.replaceState(null, '', '/revision/rechtehistorie?mockProfile=auditor')
    expect(currentMockProfile()).toBe('auditor')

    window.history.replaceState(null, '', '/revision/verbindungsprotokoll')
    expect(currentMockProfile()).toBe('auditor')
  })

  it('returns to the default with ?mockProfile=admin', () => {
    window.sessionStorage.setItem('opaa.mockProfile', 'user')
    window.history.replaceState(null, '', '/?mockProfile=admin')
    expect(currentMockProfile()).toBe('admin')
  })

  it('ignores an unknown profile and keeps the remembered one', () => {
    window.sessionStorage.setItem('opaa.mockProfile', 'user')
    window.history.replaceState(null, '', '/?mockProfile=root')
    expect(currentMockProfile()).toBe('user')
  })
})

describe('mockProfileUser', () => {
  it('keeps the regular fixture for the system administrator', () => {
    expect(mockProfileUser('admin', mockUser)).toBe(mockUser)
  })

  it.each([
    ['user', 'USER'],
    ['auditor', 'AUDITOR'],
  ] as const)('signs in the %s profile with the role %s', (profile, systemRole) => {
    expect(mockProfileUser(profile, mockUser)?.systemRole).toBe(systemRole)
  })

  it('signs in nobody for signed-out', () => {
    expect(mockProfileUser('signed-out', mockUser)).toBeNull()
  })
})
