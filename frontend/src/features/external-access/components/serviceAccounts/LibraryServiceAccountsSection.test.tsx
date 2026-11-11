import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { scenarioErrorBody } from '@/shared/mocks/mockScenario'
import { server } from '../../../../test/server'
import { answerConfirm, renderWithProviders } from '../../../../test/test-utils'
import { toDateInputValue } from '../tokenLabels'
import LibraryServiceAccountsSection from './LibraryServiceAccountsSection'

function render(libraryId: string) {
  return renderWithProviders(<LibraryServiceAccountsSection libraryId={libraryId} />, {
    withRouter: true,
  })
}

function inDays(days: number): string {
  return toDateInputValue(new Date(Date.now() + days * 86_400_000))
}

async function openManage(user: ReturnType<typeof userEvent.setup>) {
  const manage = await screen.findByRole('button', { name: /^Verwalten/ })
  await waitFor(() => expect(manage).toBeEnabled())
  await user.click(manage)
  return screen.findByRole('dialog', { name: 'Dienstkonten mit Zugriff' })
}

describe('LibraryServiceAccountsSection', () => {
  it('fasst die Dienstkonten mit Zugriff in einer Wertzeile zusammen', async () => {
    render('library-nextcloud-projekte')

    expect(
      await screen.findByText(/^Bauantrag-Portal, Wohngeld-Online · nächstes Ende/),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Dienstkonten' })).toBeInTheDocument()
  })

  it('stellt „Aufnehmen“ oben über die Liste und nimmt ein Dienstkonto aus der Radioliste auf', async () => {
    const user = userEvent.setup()
    render('library-mine')

    expect(await screen.findByText('Keines')).toBeInTheDocument()
    const dialog = await openManage(user)
    const group = await within(dialog).findByRole('radiogroup', { name: 'Dienstkonto' })
    expect(
      within(group)
        .getAllByRole('radio')
        .map((radio) => radio.closest('label')?.textContent),
    ).toEqual(['Bauantrag-Portal', 'Bürgerservice-Assistent', 'Wohngeld-Online'])
    expect(
      within(dialog).getByText('Kein Dienstkonto hat Zugriff auf diese Bibliothek.'),
    ).toBeInTheDocument()
    // the add form comes first, the list of consents after it
    const empty = within(dialog).getByText('Kein Dienstkonto hat Zugriff auf diese Bibliothek.')
    expect(group.compareDocumentPosition(empty) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await user.click(within(group).getByRole('radio', { name: 'Wohngeld-Online' }))
    await user.type(within(dialog).getByLabelText('Zugriff bis'), inDays(30))
    await user.click(within(dialog).getByRole('button', { name: 'Aufnehmen' }))

    const consents = await within(dialog).findByRole('list', { name: 'Dienstkonten mit Zugriff' })
    expect(within(consents).getByText('Wohngeld-Online')).toBeInTheDocument()
    await waitFor(() =>
      expect(within(dialog).queryByRole('radio', { name: 'Wohngeld-Online' })).toBeNull(),
    )
  })

  it('zeigt eine einzige Wahl als Zeile ohne Radioknopf', async () => {
    const user = userEvent.setup()
    render('library-nextcloud-projekte')

    const dialog = await openManage(user)
    expect(await within(dialog).findByText('Bürgerservice-Assistent')).toBeInTheDocument()
    expect(within(dialog).queryByRole('radio')).toBeNull()
  })

  it('meldet fehlende Angaben am Feld und setzt „Fehler:“ vor den Seitentitel', async () => {
    const user = userEvent.setup()
    document.title = 'Projekte · OPAA'
    render('library-mine')

    const dialog = await openManage(user)
    await within(dialog).findByRole('radiogroup', { name: 'Dienstkonto' })
    await user.click(within(dialog).getByRole('button', { name: 'Aufnehmen' }))

    expect(within(dialog).getByText('Bitte ein Dienstkonto auswählen.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Zugriff bis')).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).getAllByRole('radio')[0]).toHaveFocus()
    expect(document.title).toBe('Fehler: Projekte · OPAA')
  })

  it('sucht ab acht wählbaren Dienstkonten', async () => {
    server.use(
      http.get('/api/v1/libraries/:libraryId/service-account-choices', () =>
        HttpResponse.json({
          serviceAccounts: Array.from({ length: 8 }, (_, i) => ({
            id: `sa-${i}`,
            name: i === 3 ? 'Kita-Platzvergabe' : `Verfahren ${i}`,
            description: null,
          })),
        }),
      ),
    )
    const user = userEvent.setup()
    render('library-mine')

    const dialog = await openManage(user)
    const search = await within(dialog).findByRole('textbox', { name: 'Dienstkonto suchen' })
    expect(within(dialog).getAllByRole('radio')).toHaveLength(8)
    await user.type(search, 'kita')
    expect(within(dialog).getAllByRole('radio')).toHaveLength(1)
    await user.clear(search)
    await user.type(search, 'xyz')
    expect(within(dialog).getByText('Keine Treffer.')).toBeInTheDocument()
  })

  it('entfernt ein Dienstkonto nach Rückfrage', async () => {
    const user = userEvent.setup()
    render('library-nextcloud-projekte')

    const dialog = await openManage(user)
    const consents = await within(dialog).findByRole('list', { name: 'Dienstkonten mit Zugriff' })
    const row = within(consents).getByText('Wohngeld-Online').closest('li')!
    await user.click(within(row).getByRole('button', { name: /Wohngeld-Online/ }))
    await user.click(await screen.findByRole('menuitem', { name: 'Entfernen' }))
    await answerConfirm(user, '„Wohngeld-Online“ entfernen?', 'Entfernen')

    await waitFor(() =>
      expect(
        within(screen.getByRole('list', { name: 'Dienstkonten mit Zugriff' })).queryByText(
          'Wohngeld-Online',
        ),
      ).toBeNull(),
    )
    expect(await screen.findByText(/^Bauantrag-Portal · nächstes Ende/)).toBeInTheDocument()
  })

  it('zeigt Statuswörter für abgelaufene und deaktivierte Zustimmungen', async () => {
    server.use(
      http.get('/api/v1/libraries/:libraryId/service-accounts', () =>
        HttpResponse.json({
          maxConsentDays: 365,
          consents: [
            {
              serviceAccountId: 'sa-1',
              serviceAccountName: 'Wohngeld-Online',
              serviceAccountActive: true,
              grantedAt: '2026-01-01T00:00:00Z',
              expiresAt: '2026-02-01T00:00:00Z',
              status: 'EXPIRED',
            },
            {
              serviceAccountId: 'sa-2',
              serviceAccountName: 'Gewerbeanmeldung',
              serviceAccountActive: false,
              grantedAt: '2026-01-01T00:00:00Z',
              expiresAt: '2027-02-01T00:00:00Z',
              status: 'ACTIVE',
            },
          ],
        }),
      ),
    )
    const user = userEvent.setup()
    render('library-mine')

    expect(await screen.findByText('Keines')).toBeInTheDocument()
    const dialog = await openManage(user)
    const consents = within(dialog).getByRole('list', { name: 'Dienstkonten mit Zugriff' })
    expect(within(consents).getByText('Abgelaufen')).toBeInTheDocument()
    expect(within(consents).getByText('Deaktiviert')).toBeInTheDocument()
  })

  it('zeigt einen Ladefehler mit „Erneut laden“ und sperrt „Verwalten“', async () => {
    let fail = true
    server.use(
      http.get('/api/v1/libraries/:libraryId/service-accounts', () =>
        fail
          ? HttpResponse.json(
              scenarioErrorBody(500, 'Die Dienstkonten konnten nicht geladen werden.'),
              { status: 500 },
            )
          : HttpResponse.json({ consents: [], maxConsentDays: 365 }),
      ),
    )
    const user = userEvent.setup()
    render('library-mine')

    expect(
      await screen.findByText('Die Dienstkonten konnten nicht geladen werden.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Verwalten/ })).toBeDisabled()

    fail = false
    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(await screen.findByText('Keines')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Verwalten/ })).toBeEnabled()
  })

  it('verwirft eine Wahl, die die Suche ausblendet, statt sie aufzunehmen', async () => {
    let puts = 0
    server.use(
      http.get('*/api/v1/libraries/:libraryId/service-account-choices', () =>
        HttpResponse.json({
          serviceAccounts: Array.from({ length: 8 }, (_, i) => ({
            id: `sa-${i}`,
            name: i === 3 ? 'Kita-Platzvergabe' : `Verfahren ${i}`,
            description: null,
          })),
        }),
      ),
      http.put('*/api/v1/libraries/:libraryId/service-accounts/:id', () => {
        puts += 1
        return HttpResponse.json({})
      }),
    )
    const user = userEvent.setup()
    render('library-mine')

    const dialog = await openManage(user)
    await user.click(await within(dialog).findByRole('radio', { name: 'Kita-Platzvergabe' }))
    await user.type(
      within(dialog).getByRole('textbox', { name: 'Dienstkonto suchen' }),
      'Verfahren',
    )
    await user.type(within(dialog).getByLabelText('Zugriff bis'), inDays(30))
    await user.click(within(dialog).getByRole('button', { name: 'Aufnehmen' }))

    expect(within(dialog).getByText('Bitte ein Dienstkonto auswählen.')).toBeInTheDocument()
    expect(puts).toBe(0)
    await user.clear(within(dialog).getByRole('textbox', { name: 'Dienstkonto suchen' }))
    expect(within(dialog).getByRole('radio', { name: 'Kita-Platzvergabe' })).not.toBeChecked()
  })

  it('zeigt einen Ladefehler der Auswahl mit „Erneut laden“', async () => {
    let fail = true
    server.use(
      http.get('*/api/v1/libraries/:libraryId/service-account-choices', () =>
        fail
          ? HttpResponse.json(scenarioErrorBody(502, 'Die Auswahl konnte nicht geladen werden.'), {
              status: 502,
            })
          : HttpResponse.json({
              serviceAccounts: [
                { id: 'a', name: 'Kita-Platzvergabe', description: null },
                { id: 'b', name: 'Wohngeld-Online', description: null },
              ],
            }),
      ),
    )
    const user = userEvent.setup()
    render('library-mine')

    const dialog = await openManage(user)
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('Die wählbaren Dienstkonten konnten nicht geladen werden.')
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Erneut laden' }))
    expect(
      await within(dialog).findByRole('radio', { name: 'Kita-Platzvergabe' }),
    ).toBeInTheDocument()
  })

  it('ändert die Frist einer Zustimmung', async () => {
    const user = userEvent.setup()
    render('library-nextcloud-projekte')

    const dialog = await openManage(user)
    const consents = await within(dialog).findByRole('list', { name: 'Dienstkonten mit Zugriff' })
    const row = within(consents).getByText('Bauantrag-Portal').closest('li')!
    await user.click(within(row).getByRole('button', { name: /Bauantrag-Portal/ }))
    await user.click(await screen.findByRole('menuitem', { name: 'Frist ändern' }))

    const change = await screen.findByRole('dialog', { name: 'Frist ändern' })
    const field = within(change).getByLabelText('Zugriff bis')
    expect(field).not.toHaveValue('')
    await user.clear(field)
    const until = inDays(60)
    await user.type(field, until)
    await user.click(within(change).getByRole('button', { name: 'Speichern' }))

    const [y, m, d] = until.split('-')
    await waitFor(() =>
      expect(
        within(
          within(screen.getByRole('list', { name: 'Dienstkonten mit Zugriff' }))
            .getByText('Bauantrag-Portal')
            .closest('li')!,
        ).getByText(`${d}.${m}.${y}`),
      ).toBeInTheDocument(),
    )
  })
})
