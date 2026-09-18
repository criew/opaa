import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import ExternalAccessTokenAdminSection from './ExternalAccessTokenAdminSection'

function render() {
  return renderWithProviders(<ExternalAccessTokenAdminSection />)
}

/**
 * Die Verwaltungssicht der Zugangstokens (#1719): die Bestandsliste, ihre bewussten Lücken - kein
 * Nutzungsdatum, kein Filter nach Person - und die beiden Sperren.
 */
describe('ExternalAccessTokenAdminSection', () => {
  it('zeigt Besitzer, Name, Bibliotheken, Ablauf und Zustand', async () => {
    render()

    expect(await screen.findByText('Claude Code (Dienst-PC)')).toBeInTheDocument()
    expect(screen.getAllByText('Maria Musterfrau').length).toBe(2)
    expect(screen.getByText('Eigenes Skript')).toBeInTheDocument()
    expect(screen.getByText('abgelaufen')).toBeInTheDocument()
  })

  it('führt weder ein Nutzungsdatum noch einen Filter nach Person', async () => {
    render()

    await screen.findByText('Claude Code (Dienst-PC)')
    expect(screen.queryByText(/Zuletzt benutzt/i)).not.toBeInTheDocument()
    expect(screen.queryByText('17.09.2026')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Person/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Besitzer/i)).not.toBeInTheDocument()
    expect(screen.getByText(/Nutzungsangaben je Person gibt es hier nicht/)).toBeInTheDocument()
  })

  it('filtert über den Zustand', async () => {
    const user = userEvent.setup()
    render()

    await screen.findByText('Claude Code (Dienst-PC)')
    await user.click(screen.getByRole('combobox', { name: 'Zustand' }))
    await user.click(await screen.findByRole('option', { name: 'abgelaufen' }))

    await waitFor(() =>
      expect(screen.queryByText('Claude Code (Dienst-PC)')).not.toBeInTheDocument(),
    )
    expect(screen.getByText('Eigenes Skript')).toBeInTheDocument()
  })

  it('filtert über den bevorstehenden Ablauf', async () => {
    const user = userEvent.setup()
    render()

    await screen.findByText('Claude Code (Dienst-PC)')
    await user.click(screen.getByRole('switch', { name: 'Läuft in 30 Tagen ab' }))

    await waitFor(() =>
      expect(screen.queryByText('Claude Code (Dienst-PC)')).not.toBeInTheDocument(),
    )
    expect(screen.getByText('Cursor (Dienst-PC)')).toBeInTheDocument()
  })

  it('sperrt ein einzelnes Token nach Rückfrage', async () => {
    const user = userEvent.setup()
    render()

    await screen.findByText('Claude Code (Dienst-PC)')
    await user.click(
      screen.getByRole('button', {
        name: 'Aktionen für das Token „Claude Code (Dienst-PC)“ von Maria Musterfrau',
      }),
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Token sperren' }))
    await answerConfirm(
      user,
      'Token „Claude Code (Dienst-PC)“ von Maria Musterfrau sperren?',
      'Sperren',
    )

    await waitFor(() => expect(screen.getAllByText('gesperrt').length).toBe(1))
  })

  it('sperrt alle Tokens einer Person nach Rückfrage', async () => {
    const user = userEvent.setup()
    render()

    await screen.findByText('Claude Code (Dienst-PC)')
    await user.click(
      screen.getByRole('button', {
        name: 'Aktionen für das Token „Claude Code (Dienst-PC)“ von Maria Musterfrau',
      }),
    )
    const item = await screen.findByRole('menuitem', { name: 'Alle Tokens dieser Person sperren' })
    await user.click(item)
    const confirmation = await screen.findByRole('dialog', {
      name: 'Alle Zugangstokens von Maria Musterfrau sperren?',
    })
    expect(
      within(confirmation).getByText(/noch wirksamen Tokens dieser Person/),
    ).toBeInTheDocument()
    await answerConfirm(user, 'Alle Zugangstokens von Maria Musterfrau sperren?', 'Alle sperren')

    await waitFor(() => expect(screen.getAllByText('gesperrt').length).toBe(2))
  })
})
