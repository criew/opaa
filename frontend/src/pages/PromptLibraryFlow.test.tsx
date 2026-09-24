import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockPromptLibraries } from '../mocks/promptLibraryFixtures'
import { renderWithProviders, setMockAuthState } from '../test/test-utils'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import type { AssetGrantRequest, PromptLibraryRequest, PromptRequest } from '../types/api'
import PromptLibraryCreatePage from './PromptLibraryCreatePage'
import PromptLibraryDetailPage from './PromptLibraryDetailPage'

interface Captured {
  libraries: PromptLibraryRequest[]
  grants: Array<{ path: string; body: AssetGrantRequest }>
  prompts: PromptRequest[]
}

function captureRequests(): Captured {
  const captured: Captured = { libraries: [], grants: [], prompts: [] }
  server.events.on('request:start', async ({ request }) => {
    if (request.method !== 'POST') return
    const path = new URL(request.url).pathname
    const body = await request.clone().json()
    if (path === '/api/v1/prompt-libraries') captured.libraries.push(body)
    else if (path.endsWith('/grants')) captured.grants.push({ path, body })
    else if (path.endsWith('/prompts')) captured.prompts.push(body)
  })
  return captured
}

function renderApp() {
  return renderWithProviders(
    <Routes>
      <Route path="/prompts/new" element={<PromptLibraryCreatePage />} />
      <Route path="/prompts/:promptLibraryId" element={<PromptLibraryDetailPage />} />
      <Route path="/prompts/:promptLibraryId/:tab" element={<PromptLibraryDetailPage />} />
    </Routes>,
    { withRouter: true, initialRoute: '/prompts/new' },
  )
}

describe('Prompt-Bibliothek anlegen, füllen und freigeben', () => {
  beforeEach(() => {
    setMockAuthState()
    usePromptLibraryStore.getState().reset()
    server.events.removeAllListeners()
  })

  it('legt eine Gruppen-Bibliothek an, füllt sie mit zwei Prompts und gibt sie an eine Gruppe frei', async () => {
    const captured = captureRequests()
    const user = userEvent.setup()
    renderApp()

    // Stammdaten
    await user.type(screen.getByLabelText(/^Name/), 'Formulierungshilfen Referat 50')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    // Eigentümer: eine Gruppe, in der die Person Mitglied ist
    await user.click(screen.getByRole('radio', { name: 'Eine Gruppe' }))
    await user.click(await screen.findByLabelText('Gruppe'))
    await user.click(await screen.findByRole('option', { name: 'Projektbeteiligte Phoenix' }))
    await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))

    // Rechte: Auffindbarkeit ist aus, bis jemand sie ausdrücklich setzt
    expect(screen.getByLabelText('Im Katalog auffindbar')).not.toBeChecked()
    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    await user.type(await screen.findByLabelText('Gruppe suchen'), 'Referat')
    const [referat] = await screen.findAllByRole('option', { name: /Referat 50/ })
    await user.click(referat)
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Prompt-Bibliothek anlegen' }))

    // Detailseite der neuen Bibliothek
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Formulierungshilfen Referat 50' }),
    ).toBeInTheDocument()
    expect(captured.libraries).toEqual([
      expect.objectContaining({
        name: 'Formulierungshilfen Referat 50',
        ownerType: 'GROUP',
        ownerId: 'group-phoenix',
        visibility: 'PRIVATE',
        listed: false,
      }),
    ])
    expect(captured.grants).toHaveLength(1)
    expect(captured.grants[0].path).toMatch(/^\/api\/v1\/assets\/PROMPT_LIBRARY\/.+\/grants$/)
    expect(captured.grants[0].body).toMatchObject({
      subjectType: 'GROUP',
      subjectId: 'group-referat-50',
      role: 'VIEWER',
    })

    // Erster Prompt mit Pflicht-Variable vom Typ Datum
    await user.click(await screen.findByRole('button', { name: 'Neuer Prompt' }))
    let dialog = await screen.findByRole('dialog', { name: 'Neuer Prompt' })
    await user.type(within(dialog).getByLabelText('Titel'), 'Anhörung')
    await user.click(within(dialog).getByLabelText('Text'))
    await user.paste('Anhörung zum Aktenzeichen, Frist {{frist}}.')
    await user.click(within(dialog).getByRole('combobox', { name: 'Typ von frist' }))
    await user.click(await screen.findByRole('option', { name: 'Datum' }))
    await user.click(within(dialog).getByLabelText('frist ist Pflicht'))
    await user.click(within(dialog).getByRole('button', { name: 'Prompt speichern' }))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Neuer Prompt' })).not.toBeInTheDocument(),
    )

    // Zweiter Prompt ohne Variablen
    await user.click(screen.getByRole('button', { name: 'Neuer Prompt' }))
    dialog = await screen.findByRole('dialog', { name: 'Neuer Prompt' })
    await user.type(within(dialog).getByLabelText('Titel'), 'Vermerk')
    await user.type(within(dialog).getByLabelText('Text'), 'Fasse den Sachverhalt zusammen.')
    await user.click(within(dialog).getByRole('button', { name: 'Prompt speichern' }))
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Neuer Prompt' })).not.toBeInTheDocument(),
    )

    expect(await screen.findByText('/anhoerung')).toBeInTheDocument()
    expect(screen.getByText('/vermerk')).toBeInTheDocument()
    expect(screen.getByText('1 Variable, davon 1 Pflicht')).toBeInTheDocument()
    expect(captured.prompts).toEqual([
      expect.objectContaining({
        name: 'anhoerung',
        variables: [expect.objectContaining({ name: 'frist', type: 'DATE', required: true })],
      }),
      expect.objectContaining({ name: 'vermerk', variables: [] }),
    ])
  }, 30000)

  it('führt nach einer abgelehnten Freigabe zur angelegten Bibliothek, statt ein zweites Anlegen anzubieten', async () => {
    const captured = captureRequests()
    server.use(
      http.post('/api/v1/assets/:assetType/:assetId/grants', () =>
        HttpResponse.json({ error: 'Kein Zugriff' }, { status: 403 }),
      ),
    )
    const user = userEvent.setup()
    renderApp()

    await user.type(screen.getByLabelText(/^Name/), 'Vorlagen')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter zu Rechten' }))
    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    await user.type(await screen.findByLabelText('Gruppe suchen'), 'Referat')
    const [referat] = await screen.findAllByRole('option', { name: /Referat 50/ })
    await user.click(referat)
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Prompt-Bibliothek anlegen' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Vorlagen' })).toBeInTheDocument()
    expect(await screen.findByText(/nicht gespeichert werden: Referat 50/)).toBeInTheDocument()
    expect(captured.libraries).toHaveLength(1)
  }, 30000)

  it('nennt bei offener Nachfolge den Ausgang, wenn die Reichweite wachsen soll', async () => {
    mockPromptLibraries['prompt-library-referat-50'].succession = {
      addressee: 'SYSTEM_ADMINISTRATION',
      addresseeLabel: 'die Systemverwaltung',
    }
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/prompts/:promptLibraryId/:tab" element={<PromptLibraryDetailPage />} />
      </Routes>,
      { withRouter: true, initialRoute: '/prompts/prompt-library-referat-50/settings' },
    )

    expect(await screen.findByText(/Nachfolge offen — zuständig/)).toBeInTheDocument()
    await user.click(await screen.findByRole('combobox', { name: 'Verteilungsstufe' }))
    await user.click(await screen.findByRole('option', { name: 'organisationsweit' }))
    await user.click(screen.getByRole('button', { name: 'Freigabe speichern' }))

    expect(await screen.findByText(/Übernahme/)).toBeInTheDocument()
  }, 15000)

  it('zeigt der Verwaltung ohne Leserecht statt der Prompts den verweigerten Zugriff', async () => {
    renderWithProviders(
      <Routes>
        <Route path="/prompts/:promptLibraryId" element={<PromptLibraryDetailPage />} />
      </Routes>,
      { withRouter: true, initialRoute: '/prompts/prompt-library-verwaltet' },
    )

    expect(
      await screen.findByText('Kein Zugriff auf die Prompts dieser Prompt-Bibliothek'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Neuer Prompt' })).not.toBeInTheDocument()
    // Verwalten bleibt möglich: der Reiter „Verwaltung“ steht zur Verfügung.
    expect(screen.getByRole('tab', { name: 'Verwaltung' })).toBeInTheDocument()
  })

  it('zeigt einer Leserin die Prompts, aber weder Verwaltung noch Bearbeiten', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/prompts/:promptLibraryId" element={<PromptLibraryDetailPage />} />
      </Routes>,
      { withRouter: true, initialRoute: '/prompts/prompt-library-organisation' },
    )

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Hausweite Vorlagen' }),
    ).toBeInTheDocument()
    expect(await screen.findByText('/ablehnung')).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Verwaltung' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Neuer Prompt' })).not.toBeInTheDocument()
    await user.click(screen.getByText('Ablehnungsbescheid'))
    expect(
      screen.queryByRole('button', { name: 'Prompt Ablehnungsbescheid bearbeiten' }),
    ).not.toBeInTheDocument()
  })

  it('führt einen Verwalter in die Verwaltung mit demselben Freigabeabschnitt', async () => {
    renderWithProviders(
      <Routes>
        <Route path="/prompts/:promptLibraryId/:tab" element={<PromptLibraryDetailPage />} />
      </Routes>,
      { withRouter: true, initialRoute: '/prompts/prompt-library-referat-50/settings' },
    )

    expect(await screen.findByRole('tab', { name: 'Verwaltung' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByRole('heading', { name: 'Freigabe' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rechte verwalten' })).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Warum sehe ich diese Prompt-Bibliothek?' }),
    ).toBeInTheDocument()
    // Löschen bleibt dem Eigentümer vorbehalten.
    expect(
      screen.queryByRole('button', { name: 'Prompt-Bibliothek löschen' }),
    ).not.toBeInTheDocument()
  })
})
