import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '../../theme/theme'
import SecretField from './SecretField'

interface HarnessProps {
  stored?: boolean
  error?: string
  hint?: string
  withRemoval?: boolean
}

function Harness({ stored = false, error, hint, withRemoval = false }: HarnessProps) {
  const [value, setValue] = useState('')
  const [remove, setRemove] = useState(false)
  return (
    <ThemeProvider theme={createAppTheme('light')}>
      <SecretField
        id="secret"
        label="Passwort"
        value={value}
        onChange={setValue}
        stored={stored}
        error={error}
        hint={hint}
        removal={
          withRemoval
            ? {
                requested: remove,
                onChange: setRemove,
                removeLabel: 'Gespeichertes Passwort löschen',
                keepLabel: 'Passwort behalten',
                removedText: 'Das gespeicherte Passwort wird beim Speichern gelöscht.',
              }
            : undefined
        }
      />
      <output data-testid="state">{JSON.stringify({ value, remove })}</output>
    </ThemeProvider>
  )
}

function state() {
  return JSON.parse(screen.getByTestId('state').textContent ?? '{}') as {
    value: string
    remove: boolean
  }
}

function input() {
  return screen.getByLabelText('Passwort', { selector: 'input' })
}

describe('SecretField', () => {
  it('masks what is typed and shows it only while the eye is pressed', async () => {
    const user = userEvent.setup()
    render(<Harness hint="Mindestens 12 Zeichen." />)

    expect(input()).toHaveAttribute('type', 'password')
    expect(input()).toHaveAccessibleDescription('Mindestens 12 Zeichen.')
    await user.type(input(), 'geheim')
    expect(state().value).toBe('geheim')

    const eye = screen.getByRole('button', { name: 'Passwort anzeigen' })
    expect(eye).toHaveAttribute('aria-pressed', 'false')
    await user.click(eye)
    expect(input()).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Passwort verbergen' }))
    expect(input()).toHaveAttribute('type', 'password')
  })

  it('names a stored secret as „Gesetzt“ and opens an empty field only on „Ersetzen“', async () => {
    const user = userEvent.setup()
    render(<Harness stored />)

    expect(screen.getByText('Gesetzt')).toBeInTheDocument()
    expect(document.querySelector('input')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Passwort ersetzen' }))

    expect(input()).toHaveValue('')
    await waitFor(() => expect(input()).toHaveFocus())
    await user.click(screen.getByRole('button', { name: 'Gespeicherten Wert behalten' }))
    expect(screen.getByText('Gesetzt')).toBeInTheDocument()
    expect(state()).toEqual({ value: '', remove: false })
  })

  it('removes a stored secret through a text action that can be taken back', async () => {
    const user = userEvent.setup()
    render(<Harness stored withRemoval />)

    const remove = screen.getByRole('button', { name: 'Gespeichertes Passwort löschen' })
    // A quiet text action at the field, not a red one.
    expect(remove).toHaveClass('MuiButton-text')
    expect(remove).not.toHaveClass('MuiButton-colorError')
    await user.click(remove)

    expect(state().remove).toBe(true)
    expect(
      screen.getByText('Das gespeicherte Passwort wird beim Speichern gelöscht.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Gesetzt')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Passwort behalten' }))
    expect(state().remove).toBe(false)
    expect(screen.getByText('Gesetzt')).toBeInTheDocument()
  })

  it('offers no removal where none is passed', () => {
    render(<Harness stored />)

    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it('carries the error in every state, so a form finds the field to focus', async () => {
    const user = userEvent.setup()
    render(<Harness stored error="Bitte das Passwort neu eingeben." hint="Mindestens 12 Zeichen." />)

    const replace = screen.getByRole('button', { name: 'Passwort ersetzen' })
    expect(replace).toHaveAttribute('id', 'secret')
    expect(replace).toHaveAttribute('data-invalid', 'true')
    expect(replace).toHaveAccessibleDescription('Bitte das Passwort neu eingeben.')

    await user.click(replace)
    expect(input()).toHaveAttribute('id', 'secret')
    expect(input()).toHaveAttribute('aria-invalid', 'true')
    // The hint stays below the error: it names what the error asks for.
    expect(input()).toHaveAccessibleDescription(
      /^Bitte das Passwort neu eingeben\.\s*Mindestens 12 Zeichen\.$/,
    )
  })
})
