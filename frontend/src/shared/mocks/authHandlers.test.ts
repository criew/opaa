import { afterEach, describe, expect, it } from 'vitest'

describe('auth mocks by profile', () => {
  afterEach(() => {
    window.sessionStorage.removeItem('opaa.mockProfile')
  })

  it('signs in the system administrator without a profile', async () => {
    const me = await (await fetch('/api/v1/auth/me')).json()
    const config = await (await fetch('/api/v1/auth/config')).json()

    expect(me.systemRole).toBe('SYSTEM_ADMIN')
    expect(config.mode).toBe('dev')
  })

  it('signs in the audit role for the auditor profile', async () => {
    window.sessionStorage.setItem('opaa.mockProfile', 'auditor')

    const me = await (await fetch('/api/v1/auth/me')).json()

    expect(me.systemRole).toBe('AUDITOR')
  })

  it('answers 401 and offers every sign-in for the signed-out profile', async () => {
    window.sessionStorage.setItem('opaa.mockProfile', 'signed-out')

    const me = await fetch('/api/v1/auth/me')
    const config = await (await fetch('/api/v1/auth/config')).json()

    const afterSignIn = await fetch('/api/v1/auth/me', {
      headers: { Authorization: 'Bearer mock-local-access-token' },
    })

    expect(me.status).toBe(401)
    expect(afterSignIn.status).toBe(200)
    expect(config.mode).toBe('oidc')
    expect(config.providers).toHaveLength(2)
    expect(config.localAccounts).toMatchObject({
      enabled: true,
      selfRegistrationEnabled: true,
      passwordResetEnabled: true,
    })
  })
})
