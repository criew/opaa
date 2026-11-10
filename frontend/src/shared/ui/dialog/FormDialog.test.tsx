import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import { renderWithProviders } from '@/test/test-utils'
import FormDialog from './FormDialog'

/** A form with one required field that reports a missing value at the field on submit. */
function NameForm({ onSave, busy = false }: { onSave: (name: string) => void; busy?: boolean }) {
  const [name, setName] = useState('')
  const [missing, setMissing] = useState(false)
  return (
    <FormDialog
      title="Gruppe anlegen"
      submitLabel="Anlegen"
      onSubmit={() => {
        if (!name.trim()) setMissing(true)
        else onSave(name)
      }}
      onClose={vi.fn()}
      busy={busy}
      busyAnnouncement="Die Gruppe wird angelegt"
    >
      <TextField label="Beschreibung" />
      <TextField
        label="Name"
        value={name}
        onChange={(event) => {
          setName(event.target.value)
          setMissing(false)
        }}
        error={missing}
        helperText={missing ? 'Bitte einen Namen angeben.' : undefined}
      />
    </FormDialog>
  )
}

/** The form dialog (guidelines 5.6): fixed places, one way out, an action that is never locked. */
describe('FormDialog', () => {
  it('is named by its title and closes through „Abbrechen“ without a ✕', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <FormDialog title="Gruppe anlegen" submitLabel="Anlegen" onSubmit={vi.fn()} onClose={onClose}>
        Inhalt
      </FormDialog>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Gruppe anlegen' })
    expect(within(dialog).queryByRole('button', { name: 'Schließen' })).not.toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Abbrechen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('keeps the action available with missing input and moves the focus into the field in error', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    renderWithProviders(<NameForm onSave={onSave} />)

    const submit = screen.getByRole('button', { name: 'Anlegen' })
    expect(submit).toBeEnabled()
    expect(submit).not.toHaveAttribute('aria-disabled')
    await user.click(submit)

    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByText('Bitte einen Namen angeben.')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Name' })).toHaveFocus()
  })

  it('submits with Enter from a line field', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    renderWithProviders(<NameForm onSave={onSave} />)

    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Bauamt{Enter}')
    expect(onSave).toHaveBeenCalledWith('Bauamt')
  })

  it('leaves Enter to the field where the dialog says so', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderWithProviders(
      <FormDialog
        title="Ordner auswählen"
        submitLabel="Übernehmen"
        onSubmit={onSubmit}
        onClose={vi.fn()}
        submitOnEnter={false}
      >
        <TextField label="Suche" />
      </FormDialog>,
    )

    await user.type(screen.getByRole('textbox', { name: 'Suche' }), 'Akte{Enter}')
    expect(onSubmit).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Übernehmen' }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })

  it('is unavailable only while the action runs, and then stays open', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onClose = vi.fn()
    renderWithProviders(
      <FormDialog
        title="Gruppe anlegen"
        submitLabel="Anlegen"
        onSubmit={onSubmit}
        onClose={onClose}
        busy
        busyAnnouncement="Die Gruppe wird angelegt"
      >
        Inhalt
      </FormDialog>,
    )

    const submit = screen.getByRole('button', { name: 'Anlegen' })
    expect(submit).toHaveAttribute('aria-busy', 'true')
    expect(submit).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Die Gruppe wird angelegt')
    await user.click(submit)
    await user.keyboard('{Escape}')

    expect(onSubmit).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Abbrechen' })).toBeDisabled()
  })

  it('places the secondary action before „Abbrechen“ and the action last', () => {
    renderWithProviders(
      <FormDialog
        title="Neues Modell"
        submitLabel="Anlegen"
        onSubmit={vi.fn()}
        onClose={vi.fn()}
        secondaryAction={<Button variant="outlined">Verbindung testen</Button>}
      >
        Inhalt
      </FormDialog>,
    )

    const labels = within(screen.getByRole('dialog'))
      .getAllByRole('button')
      .map((button) => button.textContent)
    expect(labels).toEqual(['Verbindung testen', 'Abbrechen', 'Anlegen'])
  })

  it('does not hand its submit to a form around the opening button', async () => {
    const outer = vi.fn((event: React.FormEvent) => event.preventDefault())
    const onSubmit = vi.fn()
    renderWithProviders(
      <form onSubmit={outer}>
        <FormDialog title="Wert ergänzen" submitLabel="Ergänzen" onSubmit={onSubmit} onClose={vi.fn()}>
          Inhalt
        </FormDialog>
      </form>,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Ergänzen' }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(outer).not.toHaveBeenCalled()
  })

  it('puts the initial focus on „Abbrechen“ for a destroying confirmation', () => {
    renderWithProviders(
      <FormDialog
        width="confirm"
        tone="danger"
        title="„Keycloak“ löschen?"
        submitLabel="Löschen"
        onSubmit={vi.fn()}
        onClose={vi.fn()}
      >
        Folge
      </FormDialog>,
    )

    expect(screen.getByRole('button', { name: 'Abbrechen' })).toHaveFocus()
  })
})
