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

  it('bietet genau die lesbaren und freigegebenen Bibliotheken als Kacheln an', async () => {
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)
    const group = await within(dialog).findByRole('group', { name: 'Bibliotheken' })

    const tiles = await within(group).findAllByRole('checkbox')
    expect(tiles.map((tile) => tile.textContent)).toEqual([
      expect.stringContaining('Meine Dokumente'),
      expect.stringContaining('Rechtsquellen Soziales'),
      expect.stringContaining('Dienstanweisungen'),
    ])
    // Lesbar, aber nicht für Fremdzugänge freigegeben: erscheint nicht.
    expect(within(dialog).queryByText('Projektakte Phoenix')).not.toBeInTheDocument()
    for (const tile of tiles) {
      expect(tile).toHaveTextContent(/Freigabe bis \d{2}\.\d{2}\.\d{4}/)
      expect(tile).toHaveAttribute('aria-checked', 'false')
    }
  })

  it('grenzt die Kacheln per Suche ein und behält die Auswahl über die Suche hinweg', async () => {
    const user = userEvent.setup()
    let sent: string[] | null = null
    server.use(
      http.post('*/api/v1/external-access/tokens', async ({ request }) => {
        sent = ((await request.json()) as { libraryIds: string[] }).libraryIds
        return HttpResponse.json({ message: 'stop' }, { status: 409 })
      }),
    )
    render()

    const dialog = await openCreateDialog(user)
    await user.click(await within(dialog).findByRole('checkbox', { name: /Meine Dokumente/ }))
    expect(within(dialog).getByText('1 ausgewählt')).toBeInTheDocument()

    await user.type(within(dialog).getByRole('searchbox', { name: 'Bibliotheken suchen' }), 'recht')

    expect(within(dialog).getAllByRole('checkbox')).toHaveLength(1)
    await user.click(within(dialog).getByRole('checkbox', { name: /Rechtsquellen Soziales/ }))
    expect(within(dialog).getByText('2 ausgewählt')).toBeInTheDocument()

    await user.type(within(dialog).getByRole('searchbox', { name: 'Bibliotheken suchen' }), 'xyz')
    expect(within(dialog).queryAllByRole('checkbox')).toHaveLength(0)
    expect(within(dialog).getByText(/Keine Bibliothek passt zur Suche/)).toBeInTheDocument()

    await user.clear(within(dialog).getByRole('searchbox', { name: 'Bibliotheken suchen' }))
    expect(within(dialog).getByRole('checkbox', { name: /Meine Dokumente/ })).toHaveAttribute(
      'aria-checked',
      'true',
    )

    // Submitted while the search hides the chosen "Meine Dokumente": it is still sent.
    await user.type(within(dialog).getByRole('searchbox', { name: 'Bibliotheken suchen' }), 'recht')
    expect(
      within(dialog).queryByRole('checkbox', { name: /Meine Dokumente/ }),
    ).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Name / Zweck'), 'Claude Code (Notebook)')
    await user.click(within(dialog).getByRole('button', { name: 'Erzeugen' }))

    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent).toEqual(expect.arrayContaining(['library-mine', 'library-referat-50']))
  })

  describe('Filter der Auswahl', () => {
    const RELEASE = new Date(Date.now() + 300 * 86_400_000).toISOString()
    const offered = [
      {
        id: 'lib-own',
        name: 'Eigene Notizen',
        releaseExpiresAt: RELEASE,
        favorite: false,
        fromMyGroups: false,
      },
      {
        id: 'lib-fav-group',
        name: 'Rechtsquellen Soziales',
        releaseExpiresAt: RELEASE,
        favorite: true,
        fromMyGroups: true,
      },
      {
        id: 'lib-group',
        name: 'Dienstanweisungen',
        releaseExpiresAt: RELEASE,
        favorite: false,
        fromMyGroups: true,
      },
      {
        id: 'lib-fav',
        name: 'Vergaberecht',
        releaseExpiresAt: RELEASE,
        favorite: true,
        fromMyGroups: false,
      },
    ]

    beforeEach(() => {
      server.use(
        http.get('*/api/v1/external-access/eligible-libraries', () =>
          HttpResponse.json({ libraries: offered }),
        ),
      )
    })

    function tileNames(dialog: HTMLElement): string[] {
      return within(dialog)
        .queryAllByRole('checkbox')
        .map((tile) => within(tile).getAllByText(/./)[0].textContent ?? '')
    }

    it('zeigt mit „Favoriten“ nur die eigenen Favoriten', async () => {
      const user = userEvent.setup()
      render()
      const dialog = await openCreateDialog(user)
      await within(dialog).findByRole('checkbox', { name: /Eigene Notizen/ })

      const chip = within(dialog).getByRole('button', { name: 'Favoriten' })
      expect(chip).toHaveAttribute('aria-pressed', 'false')
      await user.click(chip)

      expect(chip).toHaveAttribute('aria-pressed', 'true')
      expect(tileNames(dialog)).toEqual(['Rechtsquellen Soziales', 'Vergaberecht'])
    })

    it('zeigt mit „Aus meinen Gruppen“ nur, was über die eigenen Gruppen kommt', async () => {
      const user = userEvent.setup()
      render()
      const dialog = await openCreateDialog(user)
      await within(dialog).findByRole('checkbox', { name: /Eigene Notizen/ })

      await user.click(within(dialog).getByRole('button', { name: 'Aus meinen Gruppen' }))

      expect(tileNames(dialog)).toEqual(['Rechtsquellen Soziales', 'Dienstanweisungen'])
    })

    it('kombiniert die Filter und meldet, wenn nichts mehr passt', async () => {
      const user = userEvent.setup()
      render()
      const dialog = await openCreateDialog(user)
      await within(dialog).findByRole('checkbox', { name: /Eigene Notizen/ })

      await user.click(within(dialog).getByRole('button', { name: 'Favoriten' }))
      await user.click(within(dialog).getByRole('button', { name: 'Aus meinen Gruppen' }))
      expect(tileNames(dialog)).toEqual(['Rechtsquellen Soziales'])

      await user.type(
        within(dialog).getByRole('searchbox', { name: 'Bibliotheken suchen' }),
        'eigene',
      )
      expect(tileNames(dialog)).toEqual([])
      expect(
        within(dialog).getByText('Keine Bibliothek passt zur Suche und zu den Filtern.'),
      ).toBeInTheDocument()

      // Both filters off again is "alle".
      await user.click(within(dialog).getByRole('button', { name: 'Favoriten' }))
      await user.click(within(dialog).getByRole('button', { name: 'Aus meinen Gruppen' }))
      expect(within(dialog).getByRole('button', { name: 'Favoriten' })).toHaveAttribute(
        'aria-pressed',
        'false',
      )
      expect(within(dialog).getByRole('button', { name: 'Aus meinen Gruppen' })).toHaveAttribute(
        'aria-pressed',
        'false',
      )
      expect(tileNames(dialog)).toEqual(['Eigene Notizen'])
    })

    it('zeigt mit „Nur ausgewählte“ die Auswahl beim Einschalten; Abgewähltes bleibt bis dahin stehen', async () => {
      const user = userEvent.setup()
      let sent: string[] | null = null
      server.use(
        http.post('*/api/v1/external-access/tokens', async ({ request }) => {
          sent = ((await request.json()) as { libraryIds: string[] }).libraryIds
          return HttpResponse.json({ message: 'stop' }, { status: 409 })
        }),
      )
      render()
      const dialog = await openCreateDialog(user)
      await user.click(await within(dialog).findByRole('checkbox', { name: /Eigene Notizen/ }))
      await user.click(within(dialog).getByRole('checkbox', { name: /Vergaberecht/ }))

      await user.click(within(dialog).getByRole('button', { name: 'Nur ausgewählte' }))
      expect(tileNames(dialog)).toEqual(['Eigene Notizen', 'Vergaberecht'])
      expect(within(dialog).getByText('2 ausgewählt')).toBeInTheDocument()

      await user.click(within(dialog).getByRole('checkbox', { name: /Vergaberecht/ }))
      expect(within(dialog).getByText('1 ausgewählt')).toBeInTheDocument()
      // The filter holds the choice as it stood when switched on: the tile chosen away stays, so
      // the keyboard focus keeps its place.
      expect(within(dialog).getByRole('checkbox', { name: /Vergaberecht/ })).toHaveAttribute(
        'aria-checked',
        'false',
      )
      expect(tileNames(dialog)).toEqual(['Eigene Notizen', 'Vergaberecht'])

      await user.type(within(dialog).getByLabelText('Name / Zweck'), 'Claude Code (Notebook)')
      await user.click(within(dialog).getByRole('button', { name: 'Erzeugen' }))
      await waitFor(() => expect(sent).toEqual(['lib-own']))
    })
  })

  it('lässt das Formular ohne Namen und ohne Bibliothek nicht absenden und benennt das Feld', async () => {
    const user = userEvent.setup()
    render()

    const dialog = await openCreateDialog(user)
    await within(dialog).findByRole('checkbox', { name: /Meine Dokumente/ })

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
    await user.click(await within(dialog).findByRole('checkbox', { name: /Meine Dokumente/ }))
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
    await user.click(await within(dialog).findByRole('checkbox', { name: /Meine Dokumente/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Erzeugen' }))

    const valueDialog = await screen.findByRole('dialog', { name: 'Token erzeugt' })
    expect(within(valueDialog).getByTestId('external-access-token-value')).toHaveTextContent(
      'opaa_pat_new1_geheimer_wert_nur_dieses_eine_mal',
    )
    expect(within(valueDialog).getByText(/wird nur jetzt angezeigt/)).toBeInTheDocument()
    // Der Einrichtungshinweis trägt den Wert - deshalb steht er hier und nirgends sonst, und
    // zwar nur im Claude-Code-Befehl: Die beiden JSON-Schnipsel verweisen auf Eingabe bzw.
    // Umgebungsvariable, damit der Wert nicht in einer teilbaren Datei landet.
    expect(
      within(valueDialog).getByText(/claude mcp add --scope user --transport http opaa/),
    ).toBeInTheDocument()
    expect(within(valueDialog).getByText(/\$\{input:opaa-token\}/)).toBeInTheDocument()
    expect(within(valueDialog).getByText(/\$\{env:OPAA_TOKEN\}/)).toBeInTheDocument()
    expect(
      within(valueDialog).getByText(/Ausführliche Anleitung im Handbuch, Kapitel Fremdzugänge/),
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
