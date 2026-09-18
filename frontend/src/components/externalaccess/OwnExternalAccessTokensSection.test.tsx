import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '../../mocks/server'
import {
  mockExternalAccessSettings,
  setMockExternalAccessSettings,
} from '../../mocks/externalAccessHandlers'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import OwnExternalAccessTokensSection from './OwnExternalAccessTokensSection'

function render() {
  return renderWithProviders(<OwnExternalAccessTokensSection />)
}

/**
 * Die Schaltfläche steht schon vor der Antwort da, aber gesperrt: Ohne die Höchstlaufzeit hätte der
 * Dialog keine Obergrenze. Ein Klick davor bliebe wirkungslos und ließe den Test ins Leere laufen.
 */
async function openCreateDialog(user: UserEvent) {
  const button = await screen.findByRole('button', { name: 'Token erzeugen' })
  await waitFor(() => expect(button).toBeEnabled())
  await user.click(button)
  return screen.findByRole('dialog', { name: 'Token erzeugen' })
}

/**
 * Die eigenen Zugangstokens (#1719): die Liste samt „zuletzt benutzt" und ausgesetzter Auswahl,
 * der Anlegedialog mit Aufklärung und Pflichtfeldern, die einmalige Anzeige des Werts und der
 * Widerruf. Geprüft wird über die Schnittstelle, die auch das Backend anbietet (MSW).
 */
describe('OwnExternalAccessTokensSection', () => {
  // Die ausgelieferte Voreinstellung der Installation ist „aus"; für alles außer dem eigenen Fall
  // dazu ist der Kanal offen, sonst nimmt die Ausstellung nichts an.
  beforeEach(() => {
    setMockExternalAccessSettings({ ...mockExternalAccessSettings, enabled: true })
  })

  it('zeigt die eigenen Tokens mit Präfix, Bibliotheken und dem Tag der letzten Nutzung', async () => {
    render()

    expect(await screen.findByText('Claude Code (Dienst-PC)')).toBeInTheDocument()
    expect(screen.getByText('opaa_pat_7f3a')).toBeInTheDocument()
    expect(screen.getAllByText('Rechtsquellen Soziales').length).toBeGreaterThan(0)
    expect(screen.getByText('17.09.2026')).toBeInTheDocument()
  })

  it('kennzeichnet eine ausgesetzte Bibliotheksauswahl, statt sie wegzulassen', async () => {
    render()

    await screen.findByText('Cursor (Dienst-PC)')
    expect(screen.getByText('Freigabe ausgesetzt')).toBeInTheDocument()
  })

  it('weist auf den bevorstehenden Ablauf hin', async () => {
    render()

    // Die Fixture „Cursor" läuft in sieben Tagen ab, also innerhalb der Warnfrist.
    expect(await screen.findByText(/Läuft in \d+ Tagen ab/)).toBeInTheDocument()
  })

  it('sagt bei geschlossenem Kanal, dass die Tokens nicht wirken, und sperrt das Anlegen', async () => {
    setMockExternalAccessSettings({ ...mockExternalAccessSettings, enabled: false })
    render()

    expect(
      await screen.findByText(/Fremdzugänge sind für diese Installation abgeschaltet/),
    ).toBeInTheDocument()
    expect(screen.getAllByText('wirkt derzeit nicht').length).toBeGreaterThan(0)
    // Die Ausstellung weist bei geschlossenem Kanal jede Anfrage ab (#1744) - die Schaltfläche
    // führte also in eine Absage. Widerrufen bleibt möglich.
    expect(screen.getByRole('button', { name: 'Token erzeugen' })).toBeDisabled()
    expect(
      screen.getByRole('button', { name: 'Token „Claude Code (Dienst-PC)“ widerrufen' }),
    ).toBeEnabled()
  })

  it('zeigt im Anlegedialog die Aufklärung und die Unveränderlichkeit der Auswahl', async () => {
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)

    expect(
      within(dialog).getByText(
        /verlassen mit diesem Token OPAA und unterliegen der Protokollierung/,
      ),
    ).toBeInTheDocument()
    expect(
      within(dialog).getByText(
        /Zusage, dass OPAA einzelne Abfragen nicht mitschreibt, gilt dort nicht/,
      ),
    ).toBeInTheDocument()
    expect(
      within(dialog).getByText(
        /Name, Bibliotheken und Ablauf dieses Tokens sieht auch die Systemverwaltung/,
      ),
    ).toBeInTheDocument()
    expect(within(dialog).getByText(/Auswahl lässt sich später nicht ändern/)).toBeInTheDocument()
  })

  it('lässt das Formular ohne Namen und ohne Bibliothek nicht absenden und benennt das Feld', async () => {
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)
    await within(dialog).findByLabelText(/Meine Dokumente/)

    await user.click(within(dialog).getByRole('button', { name: 'Erzeugen' }))

    expect(within(dialog).getByText(/Bitte geben Sie einen Namen an/)).toBeInTheDocument()
    expect(
      within(dialog).getByText('Bitte wählen Sie mindestens eine Bibliothek aus.'),
    ).toBeInTheDocument()
    // Nichts wurde angelegt: der Dialog steht noch offen.
    expect(screen.getByRole('dialog', { name: 'Token erzeugen' })).toBeInTheDocument()
  })

  it('lässt das Formular ohne Ablaufdatum nicht absenden', async () => {
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)
    await user.type(within(dialog).getByLabelText('Name / Zweck'), 'Claude Code (Notebook)')
    await user.click(await within(dialog).findByLabelText(/Meine Dokumente/))
    await user.clear(within(dialog).getByLabelText('Läuft ab'))

    await user.click(within(dialog).getByRole('button', { name: 'Erzeugen' }))

    expect(within(dialog).getByText(/Bitte geben Sie ein Ablaufdatum an/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Token erzeugt' })).not.toBeInTheDocument()
  })

  it('erklärt im Leerzustand, warum keine Bibliothek wählbar ist, und nennt die Ansprechstelle', async () => {
    server.use(
      http.get('*/api/v1/external-access/eligible-libraries', () =>
        HttpResponse.json({ libraries: [] }),
      ),
    )
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)

    expect(
      await within(dialog).findByText(/nicht für Fremdzugänge freigegeben/),
    ).toBeInTheDocument()
    expect(within(dialog).getByText(/sprechen Sie diese Verwaltung an/)).toBeInTheDocument()
  })

  it('zeigt den Wert genau einmal und danach nicht mehr', async () => {
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)
    await user.type(within(dialog).getByLabelText('Name / Zweck'), 'Claude Code (Notebook)')
    await user.click(await within(dialog).findByLabelText(/Meine Dokumente/))
    await user.click(within(dialog).getByRole('button', { name: 'Erzeugen' }))

    const valueDialog = await screen.findByRole('dialog', { name: 'Token erzeugt' })
    expect(within(valueDialog).getByTestId('external-access-token-value')).toHaveTextContent(
      'opaa_pat_new1_geheimer_wert_nur_dieses_eine_mal',
    )
    expect(within(valueDialog).getByText(/wird nur jetzt angezeigt/)).toBeInTheDocument()
    // Der Einrichtungshinweis trägt den Wert - deshalb steht er hier und nirgends sonst.
    expect(
      within(valueDialog).getByText(/claude mcp add --transport http opaa/),
    ).toBeInTheDocument()

    await user.click(within(valueDialog).getByRole('button', { name: 'Kopieren' }))
    expect(await navigator.clipboard.readText()).toBe(
      'opaa_pat_new1_geheimer_wert_nur_dieses_eine_mal',
    )

    await user.click(within(valueDialog).getByRole('button', { name: 'Kopiert, schließen' }))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Token erzeugt' })).not.toBeInTheDocument(),
    )
    expect(
      screen.queryByText('opaa_pat_new1_geheimer_wert_nur_dieses_eine_mal'),
    ).not.toBeInTheDocument()
    expect(await screen.findByText('Claude Code (Notebook)')).toBeInTheDocument()
  })

  it('widerruft ein Token erst nach der Rückfrage und nennt dort die Folge', async () => {
    const user = userEvent.setup()
    render()

    await user.click(
      await screen.findByRole('button', { name: 'Token „Claude Code (Dienst-PC)“ widerrufen' }),
    )
    const confirmation = await screen.findByRole('dialog', {
      name: 'Token „Claude Code (Dienst-PC)“ widerrufen?',
    })
    expect(within(confirmation).getByText(/verliert sofort den Zugriff/)).toBeInTheDocument()
    await answerConfirm(user, 'Token „Claude Code (Dienst-PC)“ widerrufen?', 'Widerrufen')

    await waitFor(() => expect(screen.getByText('widerrufen')).toBeInTheDocument())
  })
})
