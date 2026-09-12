import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
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

  it('copies the entry and says so', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    renderWithProviders(<NewPasswordHarness />)

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
    renderWithProviders(<NewPasswordHarness />)

    await userEvent.type(screen.getByLabelText('Neues Passwort'), 'Sommerregen-42x')
    await userEvent.click(screen.getByRole('button', { name: 'Kopieren' }))

    expect(
      await screen.findByText('Passwort konnte nicht kopiert werden – bitte manuell markieren.'),
    ).toBeInTheDocument()
  })
})
