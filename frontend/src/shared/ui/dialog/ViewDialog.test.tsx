import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import SecretDialog from './SecretDialog'
import SidePanel from './SidePanel'
import ViewDialog from './ViewDialog'

/** Views, the one-time secret and the side panel (guidelines 5.6). */
describe('ViewDialog', () => {
  it('closes through the ✕ in its title and has no button „Schließen“ below', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <ViewDialog title="Fehler · 3" onClose={onClose}>
        Inhalt
      </ViewDialog>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Fehler · 3' })
    const buttons = within(dialog).getAllByRole('button')
    expect(buttons).toHaveLength(1)
    expect(buttons[0]).toHaveAccessibleName('Schließen')
    expect(buttons[0]).toHaveTextContent('')
    await userEvent.click(buttons[0])
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('stays open while an action inside it runs', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <ViewDialog title="Keycloak" onClose={onClose} busy>
        Inhalt
      </ViewDialog>,
    )

    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Schließen' })).toBeDisabled()
  })
})

describe('SecretDialog', () => {
  it('carries „Kopieren“ and „Schließen“ and ignores Escape', async () => {
    const user = userEvent.setup()
    const onCopy = vi.fn()
    const onClose = vi.fn()
    renderWithProviders(
      <SecretDialog title="Token erzeugt" onCopy={onCopy} onClose={onClose}>
        opaa_pat_abc
      </SecretDialog>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Token erzeugt' })
    expect(
      within(dialog)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['Kopieren', 'Schließen'])

    await user.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Kopieren' }))
    expect(onCopy).toHaveBeenCalledTimes(1)
    await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

describe('SidePanel', () => {
  it('is named by its title and closes through the ✕ as a view', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <SidePanel open title="Nextcloud Bauamt" titleId="panel-title" onClose={onClose}>
        Inhalt
      </SidePanel>,
    )

    const panel = screen.getByRole('dialog', { name: 'Nextcloud Bauamt' })
    await userEvent.click(within(panel).getByRole('button', { name: 'Schließen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('carries no ✕ while it is a form with its own „Abbrechen“', () => {
    renderWithProviders(
      <SidePanel open form title="Bibliotheken anlegen" titleId="panel-title" onClose={vi.fn()}>
        Inhalt
      </SidePanel>,
    )

    expect(screen.queryByRole('button', { name: 'Schließen' })).not.toBeInTheDocument()
  })
})
