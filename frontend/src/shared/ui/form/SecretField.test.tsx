import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { useState, type ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { createAppTheme } from '../../theme/theme'
import SecretField from './SecretField'

type HarnessProps = Partial<ComponentProps<typeof SecretField>>

function Harness(props: HarnessProps) {
  const [value, setValue] = useState('')
  return (
    <ThemeProvider theme={createAppTheme('light')}>
      <SecretField id="secret" label="Passwort" value={value} onChange={setValue} {...props} />
    </ThemeProvider>
  )
}

describe('SecretField', () => {
  it('masks the entry and reveals it through a toggle that names its field', async () => {
    render(<Harness />)
    const field = screen.getByLabelText('Passwort')
    await userEvent.type(field, 'geheim')
    expect(field).toHaveAttribute('type', 'password')

    await userEvent.click(screen.getByRole('button', { name: 'Passwort anzeigen' }))

    expect(field).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Passwort verbergen' })).toBeInTheDocument()
  })

  // accessibility.md 2.1: revealing a long generated password may not be a mouse-only action.
  it('reaches the toggle by keyboard', async () => {
    render(<Harness />)
    screen.getByLabelText('Passwort').focus()

    await userEvent.tab()

    expect(screen.getByRole('button', { name: 'Passwort anzeigen' })).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByLabelText('Passwort')).toHaveAttribute('type', 'text')
  })

  // „Passwort wiederholen anzeigen“ is no sentence; the repetition field names its toggle itself.
  it('names the toggle after toggleName where the label does not read in front of the verb', async () => {
    render(<Harness label="Passwort wiederholen" toggleName="Wiederholung" />)
    expect(screen.queryByRole('button', { name: /wiederholen/ })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Wiederholung anzeigen' }))

    expect(screen.getByLabelText('Passwort wiederholen')).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Wiederholung verbergen' })).toBeInTheDocument()
  })

  it('offers a secret that is being set to the password manager as a new one', () => {
    render(<Harness />)
    expect(screen.getByLabelText('Passwort')).toHaveAttribute('autocomplete', 'new-password')
  })

  it('serves as the mask of a sign-in', () => {
    render(<Harness autoComplete="current-password" disabled />)
    const field = screen.getByLabelText('Passwort')
    expect(field).toHaveAttribute('autocomplete', 'current-password')
    expect(field).toBeDisabled()
  })

  it('is revealed from outside and reports the toggle', async () => {
    const onShownChange = vi.fn()
    render(<Harness shown onShownChange={onShownChange} />)
    expect(screen.getByLabelText('Passwort')).toHaveAttribute('type', 'text')

    await userEvent.click(screen.getByRole('button', { name: 'Passwort verbergen' }))

    expect(onShownChange).toHaveBeenCalledWith(false)
  })

  it('shows the error below the field and ties it to the control', () => {
    render(<Harness error="Bitte ausfüllen." />)
    expect(screen.getByLabelText('Passwort')).toHaveAccessibleDescription('Bitte ausfüllen.')
  })

  it('never mirrors a stored secret back', async () => {
    render(<Harness stored />)
    expect(screen.getByText('gesetzt')).toBeInTheDocument()
    expect(screen.queryByLabelText('Passwort')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Passwort ersetzen' }))

    expect(screen.getByLabelText('Passwort')).toHaveValue('')
  })
})
