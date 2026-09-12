import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import AuthLayout from './AuthLayout'
import NewPasswordField from './NewPasswordField'
import PasswordField from './PasswordField'

function Harness() {
  const [value, setValue] = useState('')
  return (
    <PasswordField
      id="p"
      label="Passwort"
      value={value}
      onChange={setValue}
      autoComplete="new-password"
    />
  )
}

describe('PasswordField', () => {
  it('masks the entry and reveals it on request', async () => {
    renderWithProviders(<Harness />)
    const field = screen.getByLabelText('Passwort')
    await userEvent.type(field, 'geheim')
    expect(field).toHaveAttribute('type', 'password')

    await userEvent.click(screen.getByRole('button', { name: 'Passwort anzeigen' }))

    expect(field).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Passwort verbergen' })).toBeInTheDocument()
  })

  // accessibility.md 2.1: revealing a long generated password may not be a mouse-only action.
  it('reaches the toggle by keyboard', async () => {
    renderWithProviders(<Harness />)
    screen.getByLabelText('Passwort').focus()

    await userEvent.tab()

    expect(screen.getByRole('button', { name: 'Passwort anzeigen' })).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByLabelText('Passwort')).toHaveAttribute('type', 'text')
  })
})

function NewPasswordHarness({ minLength = 12 }: { minLength?: number }) {
  const [value, setValue] = useState('')
  return (
    <NewPasswordField
      id="np"
      label="Neues Passwort"
      value={value}
      onChange={setValue}
      minLength={minLength}
    />
  )
}

describe('NewPasswordField', () => {
  it('rates the entry as orientation without blocking anything', async () => {
    renderWithProviders(<NewPasswordHarness />)
    expect(screen.queryByTestId('password-strength')).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'kurz')

    expect(screen.getByTestId('password-strength')).toHaveTextContent('Stärke: schwach')
  })

  // The generated password is of no use to anyone who cannot read it.
  it('reveals what the generator produced', async () => {
    renderWithProviders(<NewPasswordHarness />)

    await userEvent.click(screen.getByRole('button', { name: 'Sicheres Passwort erzeugen' }))

    const field = screen.getByLabelText<HTMLInputElement>('Neues Passwort')
    expect(field).toHaveAttribute('type', 'text')
    expect(field.value.length).toBeGreaterThanOrEqual(12)
    expect(screen.getByTestId('password-strength')).toHaveTextContent('Stärke: stark')
  })

  /**
   * Deliberately without the helper's own NotificationHost and inside the AuthLayout the pages
   * actually use: the app-wide host hangs in AppShell, which nothing before a session renders in -
   * so a test that relied on the helper's host would pass while the popup went nowhere in the
   * product.
   */
  it('copies the entry and says so on a screen that has no application shell', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    renderWithProviders(
      <AuthLayout>
        <NewPasswordHarness />
      </AuthLayout>,
      { withNotificationHost: false },
    )

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Kopieren' }))

    expect(writeText).toHaveBeenCalledWith('Sommerregen-42x')
    expect(await screen.findByText('Passwort kopiert.')).toBeInTheDocument()
  })

  // A failed copy must never look like a successful one: somebody who believes they copied a
  // generated password and did not has lost it.
  it('says so when copying fails', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      configurable: true,
    })
    renderWithProviders(
      <AuthLayout>
        <NewPasswordHarness />
      </AuthLayout>,
      { withNotificationHost: false },
    )

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Kopieren' }))

    expect(
      await screen.findByText('Passwort konnte nicht kopiert werden – bitte manuell markieren.'),
    ).toBeInTheDocument()
  })
})
