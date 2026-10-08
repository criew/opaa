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
    expect(row).toHaveTextContent('Wem die Bibliothek gehört, kann nicht mehr handeln.')
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
      'Wem die Prompt-Bibliothek gehört, kann nicht mehr handeln.',
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

  it('says that an entry was just found before the detection run has seen it', async () => {
    renderPage()

    await screen.findByText('Projekt Phoenix')
    const row = rowOf('Projekt Phoenix')
    expect(row).toHaveTextContent('gerade erkannt')
    expect(within(row).getByRole('button', { name: 'Nachfolge bestimmen' })).toBeInTheDocument()
  })

  it('offers only the one next step - there is no way to keep an entry open on purpose', async () => {
    renderPage('/admin/succession/grants')

    await screen.findByText('Referat 49')
    const row = rowOf('Referat 49')
    expect(within(row).getAllByRole('button')).toHaveLength(1)
  })

  it('hands the rights of a group without members over to another group', async () => {
    renderPage('/admin/succession/grants')
    const user = userEvent.setup()

    await screen.findByText('Referat 49')
    const row = rowOf('Referat 49')
    expect(row).toHaveTextContent(
      'Die Gruppe hat Rechte an 7 Objekten, aber kein aktives Mitglied mehr.',
    )
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

  it('explains the page to an account without the system role and asks the server nothing', async () => {
    const requests: string[] = []
    server.use(
      http.get('/api/v1/admin/succession', ({ request }) => {
        requests.push(request.url)
        return HttpResponse.json({ entries: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
      }),
    )
    signInAs('USER')
    renderPage()

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
    expect(requests).toEqual([])
  })
})
