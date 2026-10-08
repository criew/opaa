import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import { mockSuccessionEntries } from '../mocks/successionFixtures'
import SuccessionPage from './SuccessionPage'
import { useAuthStore } from '../stores/authStore'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'oidc',
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'user-1', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
    token: null,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,
  })
}

function renderPage(route = '/admin/succession/open') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/succession/:tab" element={<SuccessionPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

function rowOf(name: string) {
  return screen.getByRole('listitem', { name })
}

describe('SuccessionPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
  })

  // ADR-0036, Entscheidung 6: the entry point is the object; problem, responsibility and age per row.
  it('names each entry by its object, its problem in one sentence, who is responsible and since when', async () => {
    renderPage()

    expect(await screen.findByText('Bauakten Referat 50')).toHaveAttribute(
      'href',
      '/libraries/lib-1',
    )
    const row = rowOf('Bauakten Referat 50')
    expect(row).toHaveTextContent('Das Konto, dem die Bibliothek gehört, ist nicht mehr aktiv.')
    expect(row).toHaveTextContent('Zuständig: die Systemverwaltung')
    expect(row).toHaveTextContent(/offen seit \d+ Tagen/)
    expect(row).toHaveTextContent('lange offen')
    expect(row).toHaveTextContent('Anhaltspunkt für die Nachfolge: war Mitglied von Referat 50')
    expect(within(row).getByRole('button', { name: 'Nachfolge bestimmen' })).toBeInTheDocument()
  })

  it('shows how many entries each tab holds', async () => {
    renderPage()

    const tabs = await screen.findByRole('tablist', { name: 'Bereiche der Liste' })
    await waitFor(() =>
      expect(
        within(tabs).getByRole('tab', { name: 'Inhalte ohne Verantwortliche 2' }),
      ).toBeInTheDocument(),
    )
    expect(
      within(tabs).getByRole('tab', { name: 'Gruppen ohne aktive Mitglieder 1' }),
    ).toBeInTheDocument()
    expect(within(tabs).getByRole('tab', { name: 'Leere Gruppen 1' })).toBeInTheDocument()
  })

  it('links a prompt library to its own page', async () => {
    const [template] = mockSuccessionEntries.OPEN_SUCCESSION
    server.use(
      http.get('/api/v1/admin/succession', () =>
        HttpResponse.json({
          entries: [
            {
              ...template,
              assetType: 'PROMPT_LIBRARY',
              objectId: 'prompt-library-1',
              objectName: 'Formulierungshilfen',
            },
          ],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
      ),
    )
    renderPage()

    expect(await screen.findByText('Formulierungshilfen')).toHaveAttribute(
      'href',
      '/prompts/prompt-library-1',
    )
    expect(rowOf('Formulierungshilfen')).toHaveTextContent(
      'Das Konto, dem die Prompt-Bibliothek gehört, ist nicht mehr aktiv.',
    )
  })

  // Personalrat E1/Z7: no evaluation axis "person" - neither as a filter field nor as a sort order.
  it('offers no filter or sort by previous owner or acting person', async () => {
    renderPage()
    await screen.findByText('Bauakten Referat 50')

    expect(screen.queryByRole('textbox', { name: /eigentümer|person|inhaber/i })).toBeNull()
    expect(screen.queryByRole('combobox', { name: /eigentümer|person|sortier/i })).toBeNull()
    expect(screen.queryByRole('columnheader')).toBeNull()
    // The former owner stands as text in the row - readable, but no key.
    expect(screen.getByText(/bisher: Andrea Vogt/)).toBeInTheDocument()
  })

  it('keeps an entry open on purpose with its reason and reloads the list', async () => {
    const reviews: Array<{ caseId: string; reason: string }> = []
    server.use(
      http.post('/api/v1/admin/succession/:caseId/reviews', async ({ params, request }) => {
        const body = (await request.json()) as { reason: string }
        reviews.push({ caseId: String(params.caseId), reason: body.reason })
        return HttpResponse.json(
          {
            id: 'review-1',
            caseId: String(params.caseId),
            reviewedAt: '2026-09-22T08:00:00Z',
            reason: body.reason,
          },
          { status: 201 },
        )
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await screen.findByText('Bauakten Referat 50')
    const row = rowOf('Bauakten Referat 50')
    await user.click(within(row).getByRole('button', { name: 'Bewusst offen lassen …' }))
    const reason = within(row).getByLabelText('Warum bleibt der Eintrag vorerst offen?')
    expect(reason).toHaveFocus()
    await user.type(reason, 'Nachfolge in Klärung')
    await user.click(within(row).getByRole('button', { name: 'Festhalten' }))

    await waitFor(() =>
      expect(reviews).toEqual([{ caseId: 'case-1', reason: 'Nachfolge in Klärung' }]),
    )
  })

  // Without a record of the detection run there is nothing a note could be written against.
  it('offers no "keep open" for an entry the detection run has not seen yet', async () => {
    renderPage()

    await screen.findByText('Projekt Phoenix')
    const row = rowOf('Projekt Phoenix')
    expect(row).toHaveTextContent('gerade erkannt')
    expect(within(row).queryByRole('button', { name: 'Bewusst offen lassen …' })).toBeNull()
    expect(within(row).getByRole('button', { name: 'Nachfolge bestimmen' })).toBeInTheDocument()
  })

  it('hands the rights of a group without members over to another group', async () => {
    renderPage('/admin/succession/grants')
    const user = userEvent.setup()

    await screen.findByText('Referat 49')
    const row = rowOf('Referat 49')
    expect(row).toHaveTextContent(
      'Die Gruppe hat Rechte an 7 Objekten, aber kein aktives Mitglied mehr.',
    )
    expect(row).toHaveTextContent('Bewusst offen gelassen am')
    expect(row).toHaveTextContent('Reorganisation läuft')
    await user.click(within(row).getByRole('button', { name: 'Rechte übergeben' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('button', { name: /vorschau erstellen/i })).toBeInTheDocument()
  })

  // A group that holds nothing has nothing to hand over: the way out is the group management.
  it('sends an empty group to the group management instead of offering a transfer', async () => {
    renderPage('/admin/succession/groups')

    await screen.findByText('Arbeitskreis Digitalisierung')
    const row = rowOf('Arbeitskreis Digitalisierung')
    expect(row).toHaveTextContent('Die Gruppe hat weder aktive Mitglieder noch Rechte.')
    expect(within(row).getByRole('link', { name: 'Zur Gruppenverwaltung' })).toHaveAttribute(
      'href',
      '/admin/groups',
    )
    expect(within(row).queryByRole('button', { name: 'Rechte übergeben' })).toBeNull()
  })

  it('explains when something will show up in an empty tab', async () => {
    server.use(
      http.get('/api/v1/admin/succession', () =>
        HttpResponse.json({ entries: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      ),
    )
    renderPage()

    expect(await screen.findByText('Zurzeit ist hier nichts offen.')).toBeInTheDocument()
    expect(
      screen.getByText(
        /Wird das Konto einer Person gesperrt, der eine Bibliothek oder ein Space gehört/,
      ),
    ).toBeInTheDocument()
  })

  // Between two runs a follow-up page can empty - that is no "all in order", and the way back to
  // page 1 must stay.
  it('does not claim everything is in order on an empty follow-up page', async () => {
    server.use(
      http.get('/api/v1/admin/succession', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
        return HttpResponse.json({
          entries: page === 0 ? mockSuccessionEntries.OPEN_SUCCESSION : [],
          page,
          size: 50,
          totalElements: 2,
          totalPages: 2,
        })
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await screen.findByText('Bauakten Referat 50')
    const pagination = screen.getByRole('navigation', { name: 'Seiten der Liste' })
    await user.click(within(pagination).getByRole('button', { name: /2/ }))

    expect(await screen.findByText(/Diese Seite ist inzwischen leer/)).toBeInTheDocument()
    expect(screen.queryByText('Zurzeit ist hier nichts offen.')).toBeNull()
    expect(
      within(screen.getByRole('navigation', { name: 'Seiten der Liste' })).getByRole('button', {
        name: /1/,
      }),
    ).toBeInTheDocument()
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderPage()

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
