import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { resetMockFavorites } from '../mocks/assetFixtures'
import {
  answerConfirm,
  renderWithProviders,
  setMockAuthState,
  waitForDialogClosed,
} from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
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

    // Rechte
    await user.type(await screen.findByLabelText('Person oder Gruppe suchen'), 'Referat')
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
    await waitForDialogClosed()

    // Zweiter Prompt ohne Variablen
    await user.click(screen.getByRole('button', { name: 'Neuer Prompt' }))
    dialog = await screen.findByRole('dialog', { name: 'Neuer Prompt' })
    await user.type(within(dialog).getByLabelText('Titel'), 'Vermerk')
    await user.type(within(dialog).getByLabelText('Text'), 'Fasse den Sachverhalt zusammen.')
    await user.click(within(dialog).getByRole('button', { name: 'Prompt speichern' }))
    await waitForDialogClosed()

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
  }, 60000)

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
    await user.type(await screen.findByLabelText('Person oder Gruppe suchen'), 'Referat')
    const [referat] = await screen.findAllByRole('option', { name: /Referat 50/ })
    await user.click(referat)
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Prompt-Bibliothek anlegen' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Vorlagen' })).toBeInTheDocument()
    expect(await screen.findByText(/nicht gespeichert werden: Referat 50/)).toBeInTheDocument()
    expect(captured.libraries).toHaveLength(1)
  }, 30000)

  it('zeigt der Verwaltung ohne Leserecht statt der Prompts den verweigerten Zugriff', async () => {
    renderDetail('/prompts/prompt-library-verwaltet')

    expect(
      await screen.findByText('Kein Zugriff auf die Prompts dieser Prompt-Bibliothek'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Neuer Prompt' })).not.toBeInTheDocument()
    // Verwalten bleibt möglich: Freigaben und Zuordnungen stehen zur Verfügung.
    expect(tabNames()).toEqual(['Prompts', 'Freigaben', 'Zuordnungen'])
  })
})

/** Shows the address the page ended up at, so a redirect is visible to the test. */
function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname}</output>
}

function renderDetail(initialRoute: string) {
  const page = (
    <>
      <PromptLibraryDetailPage />
      <LocationProbe />
    </>
  )
  return renderWithProviders(
    <Routes>
      <Route path="/prompts/:promptLibraryId" element={page} />
      <Route path="/prompts/:promptLibraryId/:tab" element={page} />
      <Route path="/catalog" element={<p>Katalogseite</p>} />
    </Routes>,
    { withRouter: true, initialRoute },
  )
}

function tabNames(): string[] {
  return screen.getAllByRole('tab').map((tab) => tab.textContent ?? '')
}

describe('Detailseite einer Prompt-Bibliothek (#2208)', () => {
  beforeEach(() => {
    setMockAuthState()
    usePromptLibraryStore.getState().reset()
    server.events.removeAllListeners()
    resetMockFavorites()
  })

  afterEach(() => {
    resetMockFavorites()
  })

  it('zeigt einer Leserin alle drei Reiter schreibgeschützt, ohne Zurück und ohne Verwaltung', async () => {
    const user = userEvent.setup()
    renderDetail('/prompts/prompt-library-organisation')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Hausweite Vorlagen' }),
    ).toBeInTheDocument()
    expect(await screen.findByText('/ablehnung')).toBeInTheDocument()
    expect(tabNames()).toEqual(['Prompts', 'Freigaben', 'Zuordnungen'])
    expect(screen.queryByText(/zurück zum katalog/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Neuer Prompt' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Name und Beschreibung bearbeiten' }),
    ).not.toBeInTheDocument()
    // Keine Abschnittsüberschrift wiederholt den Reiternamen.
    expect(screen.queryByRole('heading', { name: 'Prompts' })).not.toBeInTheDocument()
    await user.click(screen.getByText('Ablehnungsbescheid'))
    expect(
      screen.queryByRole('button', { name: 'Prompt Ablehnungsbescheid bearbeiten' }),
    ).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Freigaben' }))
    expect(await screen.findByRole('heading', { name: 'Eigentümer' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Berechtigungen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Eigentum übergeben' })).not.toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Warum sehe ich diese Prompt-Bibliothek?' }),
    ).toBeInTheDocument()

    // Lesen schließt das Verwenden nicht aus; Lösen hängt am Space, siehe AssetSpacesList.test.
    await user.click(screen.getByRole('tab', { name: 'Zuordnungen' }))
    expect(await screen.findByText('Engineering')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '„Hausweite Vorlagen“ in Space verwenden' }),
    ).toBeInTheDocument()
  }, 15000)

  it('leitet die frühere Adresse …/settings auf den Reiter „Freigaben“ weiter', async () => {
    renderDetail('/prompts/prompt-library-referat-50/settings')

    expect(await screen.findByRole('tab', { name: 'Freigaben' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/prompts/prompt-library-referat-50/freigaben',
    )
    expect(await screen.findByRole('heading', { name: 'Berechtigungen' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Freigeben' })).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Warum sehe ich diese Prompt-Bibliothek?' }),
    ).toBeInTheDocument()
    // Die Zuordnungen haben einen eigenen Reiter, die Stammdaten stehen im Kopf.
    expect(screen.queryByRole('heading', { name: 'Zuordnungen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Stammdaten' })).not.toBeInTheDocument()
  })

  it('leitet eine unbekannte Reiter-Adresse auf die Prompts', async () => {
    renderDetail('/prompts/prompt-library-referat-50/unbekannt')

    expect(await screen.findByRole('tab', { name: 'Prompts' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByTestId('location')).toHaveTextContent(
      /^\/prompts\/prompt-library-referat-50$/,
    )
  })

  it('bearbeitet Name und Beschreibung über den Stift im Kopf', async () => {
    const puts: unknown[] = []
    server.events.on('request:start', async ({ request }) => {
      const path = new URL(request.url).pathname
      if (
        request.method === 'PUT' &&
        path === '/api/v1/prompt-libraries/prompt-library-referat-50'
      ) {
        puts.push(await request.clone().json())
      }
    })
    const user = userEvent.setup()
    renderDetail('/prompts/prompt-library-referat-50')

    await user.click(
      await screen.findByRole('button', { name: 'Name und Beschreibung bearbeiten' }),
    )
    const nameField = screen.getByLabelText('Name der Prompt-Bibliothek')
    await user.clear(nameField)
    await user.type(nameField, 'Textbausteine Bürgerbüro')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    // The form closes only after the save; until then the h1 is the form's hidden one.
    await waitFor(() =>
      expect(screen.queryByLabelText('Name der Prompt-Bibliothek')).not.toBeInTheDocument(),
    )
    expect(
      screen.getByRole('heading', { level: 1, name: 'Textbausteine Bürgerbüro' }),
    ).toBeInTheDocument()
    expect(puts).toEqual([
      {
        name: 'Textbausteine Bürgerbüro',
        description: 'Anhörung, Vermerk und Ablehnung nach Hausstandard',
      },
    ])
  }, 15000)

  it('setzt den Stern im Kopf wie im Katalog', async () => {
    const user = userEvent.setup()
    renderDetail('/prompts/prompt-library-referat-50')

    await user.click(
      await screen.findByRole('button', {
        name: '„Formulierungshilfen Referat 50“ als Favorit markieren',
      }),
    )

    expect(
      await screen.findByRole('button', {
        name: '„Formulierungshilfen Referat 50“ aus den Favoriten entfernen',
      }),
    ).toBeInTheDocument()
  })

  it('bietet „In Space verwenden“ im Menü und im Reiter „Zuordnungen“, Löschen nur dem Eigentümer', async () => {
    const user = userEvent.setup()
    renderDetail('/prompts/prompt-library-referat-50/zuordnungen')

    expect(await screen.findByRole('tab', { name: 'Zuordnungen' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(
      await screen.findByRole('button', {
        name: '„Formulierungshilfen Referat 50“ in Space verwenden',
      }),
    ).toBeInTheDocument()
    expect(await screen.findByText('Engineering')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Weitere Aktionen' }))
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['In Space verwenden'])
  })

  it('führt einen Eigentümer über „Löschen“ im Menü zurück in den Katalog', async () => {
    const user = userEvent.setup()
    renderDetail('/prompts/prompt-library-verwaltet')

    await user.click(await screen.findByRole('button', { name: 'Weitere Aktionen' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, 'Prompt-Bibliothek „Vorlagen Personalrat“ löschen?', 'Löschen')

    expect(await screen.findByText('Katalogseite')).toBeInTheDocument()
  })

  it('kennzeichnet die Rolle der Systemverwaltung ohne eigene Berechtigung als „administrativ“', async () => {
    useAuthStore.setState({
      user: {
        id: 'admin-1',
        email: 'admin@opaa.local',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    try {
      const { unmount } = renderDetail('/prompts/prompt-library-verwaltet')
      expect(await screen.findByText('administrativ')).toBeInTheDocument()
      unmount()

      // Dieselbe Rolle wie nach der Formel: kein Etikett.
      renderDetail('/prompts/prompt-library-referat-50')
      expect(
        await screen.findByRole('button', {
          name: '„Formulierungshilfen Referat 50“ als Favorit markieren',
        }),
      ).toBeInTheDocument()
      expect(screen.queryByText('administrativ')).not.toBeInTheDocument()
    } finally {
      useAuthStore.setState({ user: null })
    }
  })
})
