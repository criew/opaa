import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import { mockSuccessionEntries } from '../mocks/successionFixtures'
import SuccessionPage from './SuccessionPage'
import { useAuthStore } from '@/shared/session/authStore'

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
    // „lange offen" is a state (guidelines 5.5): a decorative dot before its word, not a label.
    const dot = within(row).getByText('lange offen').previousElementSibling
    expect(dot).toHaveAttribute('aria-hidden', 'true')
    expect(dot).toHaveStyle({ borderRadius: '50%' })
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

  // regression guard for #2340: no account holds the asset of a dissolved directory group, so the
  // handover between two accounts cannot move it - the transfer names the owning group as source.
  it('hands an asset of a dissolved directory group over with that group as the source', async () => {
    const [template] = mockSuccessionEntries.OPEN_SUCCESSION
    const previews: unknown[] = []
    server.use(
      http.get('/api/v1/admin/succession', () =>
        HttpResponse.json({
          entries: [
            {
              ...template,
              objectId: 'lib-51',
              objectName: 'Bauakten Referat 51',
              ownerHint: 'Referat 51',
              membershipHints: [],
              ownerType: 'GROUP',
              ownerGroupId: 'group-referat-51',
            },
          ],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
      ),
      http.post('/api/v1/permission-transfers/preview', async ({ request }) => {
        previews.push(await request.json())
        return HttpResponse.json({}, { status: 400 })
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await screen.findByText('Bauakten Referat 51')
    const row = rowOf('Bauakten Referat 51')
    expect(row).toHaveTextContent(
      'Die Gruppe, der die Bibliothek gehört, gibt es nicht mehr oder sie hat keine aktiven Mitglieder.',
    )
    expect(within(row).queryByRole('button', { name: 'Nachfolge bestimmen' })).toBeNull()
    await user.click(within(row).getByRole('button', { name: 'Rechte übergeben' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Bisherige Gruppe')).toHaveValue('Referat 51')
    await user.type(within(dialog).getByLabelText('Neue Gruppe'), 'Referat 5')
    await user.click(
      await screen.findByRole('option', { name: /Referat 5 Projektteam/ }, { timeout: 3000 }),
    )
    await user.click(within(dialog).getByRole('button', { name: /vorschau erstellen/i }))

    await waitFor(() => expect(previews).toHaveLength(1))
    expect(previews[0]).toMatchObject({
      sourceType: 'GROUP',
      sourceId: 'group-referat-51',
      targetType: 'GROUP',
    })
    expect((previews[0] as { scope: string[] }).scope).toContain('OWNERSHIP')
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

  it('reads the figures of all tabs with one request and never as a list', async () => {
    const counts: string[] = []
    const listSizes: string[] = []
    server.use(
      http.get('/api/v1/admin/succession/counts', ({ request }) => {
        counts.push(request.url)
        return HttpResponse.json({
          openSuccession: 4,
          grantsWithoutRecipient: 1,
          groupWithoutEffect: 0,
        })
      }),
      http.get('/api/v1/admin/succession', ({ request }) => {
        listSizes.push(new URL(request.url).searchParams.get('size') ?? '')
        return HttpResponse.json({
          entries: [],
          page: 0,
          size: 50,
          totalElements: 0,
          totalPages: 0,
        })
      }),
    )
    renderPage()

    expect(
      await screen.findByRole('tab', { name: /Inhalte ohne Verantwortliche.*4/ }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('tab', { name: /Gruppen ohne aktive Mitglieder.*1/ }),
    ).toBeInTheDocument()
    expect(counts).toHaveLength(1)
    expect(listSizes).not.toContain('1')
  })

  it('explains the page to an account without the system role and asks the server nothing', async () => {
    const requests: string[] = []
    server.use(
      http.get('/api/v1/admin/succession', ({ request }) => {
        requests.push(request.url)
        return HttpResponse.json({ entries: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
      }),
      http.get('/api/v1/admin/succession/counts', ({ request }) => {
        requests.push(request.url)
        return HttpResponse.json({
          openSuccession: 0,
          grantsWithoutRecipient: 0,
          groupWithoutEffect: 0,
        })
      }),
    )
    signInAs('USER')
    renderPage()

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
    expect(requests).toEqual([])
  })
})
