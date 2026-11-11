import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, Route, Routes } from 'react-router'
import TextField from '@mui/material/TextField'
import { renderWithProviders } from '@/test/test-utils'
import ConnectionTestButton from './ConnectionTestButton'
import SettingsForm from './SettingsForm'

const SAVED = 'scanner.example.org'

/** A form with one field that must not be empty; `onSave` receives what would be stored. */
function HostForm({ onSave, onTest }: { onSave: (host: string) => void; onTest?: () => void }) {
  const [host, setHost] = useState(SAVED)
  const [missing, setMissing] = useState(false)
  return (
    <SettingsForm
      aria-label="Virenscanner"
      busy={false}
      dirty={host !== SAVED}
      onDiscard={() => {
        setHost(SAVED)
        setMissing(false)
      }}
      onSave={() => {
        if (host.trim() === '') setMissing(true)
        else onSave(host)
      }}
      secondaryAction={onTest && <ConnectionTestButton busy={false} onTest={onTest} />}
    >
      <TextField label="Beschreibung" />
      <TextField
        label="Host"
        value={host}
        onChange={(event) => {
          setHost(event.target.value)
          setMissing(false)
        }}
        error={missing}
        helperText={missing ? 'Host fehlt' : undefined}
      />
    </SettingsForm>
  )
}

function renderOnPage(onSave = vi.fn()) {
  renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <>
            <Link to="/andere">Andere Seite</Link>
            <HostForm onSave={onSave} />
          </>
        }
      />
      <Route path="/andere" element={<p>Andere Seite geöffnet</p>} />
    </Routes>,
    { withRouter: true },
  )
}

describe('SettingsForm', () => {
  it('shows one primary „Speichern“ that is active without a change', () => {
    renderWithProviders(<HostForm onSave={vi.fn()} onTest={vi.fn()} />)

    const form = screen.getByRole('form', { name: 'Virenscanner' })
    const buttons = within(form).getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(['Speichern', 'Verbindung testen'])
    expect(buttons[0]).toBeEnabled()
    expect(buttons[0]).toHaveAttribute('type', 'submit')
    expect(buttons[0]).toHaveClass('MuiButton-contained')
    expect(buttons[1]).toHaveClass('MuiButton-outlined')
  })

  it('saves nothing without a change and says so in a popup', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    renderWithProviders(<HostForm onSave={onSave} />)

    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(onSave).not.toHaveBeenCalled()
    expect(await screen.findByText('Es gibt keine Änderungen zu speichern.')).toBeInTheDocument()
  })

  it('offers „Verwerfen“ only while something is changed and returns to the saved state', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HostForm onSave={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Verwerfen' })).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('Host'), '.intern')
    await user.click(screen.getByRole('button', { name: 'Verwerfen' }))

    expect(screen.getByLabelText('Host')).toHaveValue(SAVED)
    expect(screen.queryByRole('button', { name: 'Verwerfen' })).not.toBeInTheDocument()
  })

  it('saves a change, also with Enter in a field', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    renderWithProviders(<HostForm onSave={onSave} />)

    await user.type(screen.getByLabelText('Host'), '.intern{Enter}')

    expect(onSave).toHaveBeenCalledWith(`${SAVED}.intern`)
  })

  it('puts the focus into the field that reported an error', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    renderWithProviders(<HostForm onSave={onSave} />)

    await user.clear(screen.getByLabelText('Host'))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(onSave).not.toHaveBeenCalled()
    expect(await screen.findByText('Host fehlt')).toBeInTheDocument()
    expect(screen.getByLabelText('Host')).toHaveFocus()
  })

  it('runs the connection test without a change', async () => {
    const user = userEvent.setup()
    const onTest = vi.fn()
    renderWithProviders(<HostForm onSave={vi.fn()} onTest={onTest} />)

    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    expect(onTest).toHaveBeenCalledTimes(1)
  })

  it('leaves through a link without a question while nothing is changed', async () => {
    const user = userEvent.setup()
    renderOnPage()

    await user.click(screen.getByRole('link', { name: 'Andere Seite' }))

    expect(await screen.findByText('Andere Seite geöffnet')).toBeInTheDocument()
  })

  it('asks „Änderungen verwerfen?“ before leaving with changes and stays on „Abbrechen“', async () => {
    const user = userEvent.setup()
    renderOnPage()
    await user.type(screen.getByLabelText('Host'), '.intern')

    await user.click(screen.getByRole('link', { name: 'Andere Seite' }))

    const question = await screen.findByRole('dialog', { name: 'Änderungen verwerfen?' })
    await user.click(within(question).getByRole('button', { name: 'Abbrechen' }))
    expect(screen.queryByText('Andere Seite geöffnet')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Host')).toHaveValue(`${SAVED}.intern`)
  })

  it('follows the link once the question is answered with „Verwerfen“', async () => {
    const user = userEvent.setup()
    renderOnPage()
    await user.type(screen.getByLabelText('Host'), '.intern')

    await user.click(screen.getByRole('link', { name: 'Andere Seite' }))
    const question = await screen.findByRole('dialog', { name: 'Änderungen verwerfen?' })
    await user.click(within(question).getByRole('button', { name: 'Verwerfen' }))

    expect(await screen.findByText('Andere Seite geöffnet')).toBeInTheDocument()
  })
})
