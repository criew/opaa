import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '../session/authStore'
import { mockLocalAccount } from '../mocks/authFixtures'
import RecentLoginDialog, {
  CONFIRMED_ANNOUNCEMENT,
  REDIRECT_ANNOUNCEMENT,
  WRONG_PASSWORD_MESSAGE,
} from './RecentLoginDialog'

const PURPOSE = 'Bevor Sie Ihre Daten herunterladen, bestätigen Sie bitte Ihre Anmeldung.'

function renderDialog() {
  const onConfirmed = vi.fn()
  const onClose = vi.fn()
  renderWithProviders(
    <RecentLoginDialog
      open
      purpose={PURPOSE}
      onConfirmed={onConfirmed}
      onClose={onClose}
      returnTo="/settings/daten"
    />,
  )
  return { onConfirmed, onClose }
}

function status() {
  return screen
    .getAllByRole('status')
    .map((region) => region.textContent)
    .join('')
}

describe('RecentLoginDialog', () => {
  beforeEach(() => {
    setMockAuthState()
  })

  describe('local session', () => {
    beforeEach(() => {
      useAuthStore.setState({ mode: 'oidc', sessionKind: 'local', token: 'old-token' })
    })

    it('is operated by keyboard alone and announces the confirmation', async () => {
      const user = userEvent.setup()
      const { onConfirmed } = renderDialog()

      expect(screen.getByRole('dialog', { name: 'Anmeldung bestätigen' })).toBeInTheDocument()
      expect(screen.getByText(PURPOSE)).toBeInTheDocument()
      const field = screen.getByLabelText('Ihr Passwort')
      expect(field).toHaveFocus()

      await user.keyboard(`${mockLocalAccount.password}{Enter}`)

      await waitFor(() => expect(onConfirmed).toHaveBeenCalledTimes(1))
      expect(useAuthStore.getState().token).toBe('mock-recent-login-token')
      expect(status()).toContain(CONFIRMED_ANNOUNCEMENT)
    })

    it('reports a wrong password as an alert and keeps the dialog open', async () => {
      const user = userEvent.setup()
      const { onConfirmed } = renderDialog()

      await user.keyboard('falsch-geraten{Enter}')

      expect(await screen.findByRole('alert')).toHaveTextContent(WRONG_PASSWORD_MESSAGE)
      expect(onConfirmed).not.toHaveBeenCalled()
      expect(useAuthStore.getState().token).toBe('old-token')
      expect(screen.getByLabelText('Ihr Passwort')).toHaveValue('')
    })

    it('closes on Escape', async () => {
      const user = userEvent.setup()
      const { onClose, onConfirmed } = renderDialog()

      await user.keyboard('{Escape}')

      expect(onClose).toHaveBeenCalledTimes(1)
      expect(onConfirmed).not.toHaveBeenCalled()
    })
  })

  it('forwards a provider session to a forced sign-in and announces the redirect', async () => {
    const confirmSignInAtProvider = vi.fn(() => new Promise<void>(() => {}))
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'oidc', confirmSignInAtProvider })
    const user = userEvent.setup()
    const { onConfirmed } = renderDialog()

    expect(screen.queryByLabelText('Ihr Passwort')).not.toBeInTheDocument()
    const button = screen.getByRole('button', { name: 'Erneut anmelden' })
    expect(button).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(confirmSignInAtProvider).toHaveBeenCalledWith('/settings/daten')
    await waitFor(() => expect(status()).toContain(REDIRECT_ANNOUNCEMENT))
    expect(onConfirmed).not.toHaveBeenCalled()
  })

  it('explains a provider that is gone instead of leaving silently', async () => {
    useAuthStore.setState({
      mode: 'oidc',
      sessionKind: 'oidc',
      confirmSignInAtProvider: vi.fn(() => Promise.reject(new Error('Der Anmeldedienst fehlt.'))),
    })
    const user = userEvent.setup()
    renderDialog()

    await user.click(screen.getByRole('button', { name: 'Erneut anmelden' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Der Anmeldedienst fehlt.')
  })

  it('only asks to continue in the development mode, which has no sign-in', async () => {
    const user = userEvent.setup()
    const { onConfirmed } = renderDialog()

    await user.click(screen.getByRole('button', { name: 'Fortfahren' }))

    expect(onConfirmed).toHaveBeenCalledTimes(1)
  })
})
