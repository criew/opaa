import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { useAgentAvailability } from '@/shared/session/agentAvailability'
import { mockMyCapabilities } from '@/features/capability/mocks/capabilityFixtures'
import type { AgentUpdateRequest } from '@/shared/api/types'
import { useAgentStore } from '../stores/agentStore'
import { mockAgentSettings } from '../mocks/agentFixtures'
import AgentCreatePage from './AgentCreatePage'
import AgentDetailPage from './AgentDetailPage'
import AgentSettingsPage from './AgentSettingsPage'

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/agents/new" element={<AgentCreatePage />} />
      <Route path="/agents/:agentId" element={<AgentDetailPage />} />
      <Route path="/agents/:agentId/:tab" element={<AgentDetailPage />} />
      <Route path="/admin/agents" element={<AgentSettingsPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

function captureUpdates(): AgentUpdateRequest[] {
  const updates: AgentUpdateRequest[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PUT' && new URL(request.url).pathname.startsWith('/api/v1/agents/')) {
      updates.push(await request.clone().json())
    }
  })
  return updates
}

describe('Agenten', () => {
  beforeEach(() => {
    setMockAuthState()
    useAgentStore.getState().reset()
    useAgentAvailability.getState().adopt(true)
    server.events.removeAllListeners()
  })

  it('legt einen Agenten nur mit Name und Zweck an und öffnet seine Seite', async () => {
    const user = userEvent.setup()
    renderAt('/agents/new')

    await user.type(await screen.findByLabelText(/^Name/), 'Vergabeprüfung')
    await user.type(screen.getByLabelText(/^Zweck/), 'Prüft Vergabeunterlagen.')
    await user.click(screen.getByRole('button', { name: 'Agent anlegen' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Vergabeprüfung' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        'Noch kein Auftrag. Der Agent arbeitet nur nach den festen Regeln von OPAA.',
      ),
    ).toBeInTheDocument()
  })

  it('zeigt ohne Anlegerecht weder Formular noch Anlegen-Knopf', async () => {
    const original = [...mockMyCapabilities.capabilities]
    mockMyCapabilities.capabilities = original.filter((c) => c !== 'CREATE_AGENT')
    try {
      renderAt('/agents/new')
      expect(
        await screen.findByText(/Ihnen fehlt das Anlegerecht „Agenten anlegen“/),
      ).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Agent anlegen' })).not.toBeInTheDocument()
    } finally {
      mockMyCapabilities.capabilities = original
    }
  })

  it('fasst einen fehlenden Namen als Fehler zusammen und setzt den Fokus darauf', async () => {
    const user = userEvent.setup()
    renderAt('/agents/new')

    await user.click(await screen.findByRole('button', { name: 'Agent anlegen' }))

    const summary = await screen.findByRole('alert')
    expect(within(summary).getByText('Fehler: 1 Angabe prüfen')).toBeInTheDocument()
    await waitFor(() => expect(summary).toHaveFocus())
  })

  it('ändert den Auftrag im Lese-Raster und schickt die übrigen Angaben unverändert mit', async () => {
    const updates = captureUpdates()
    const user = userEvent.setup()
    renderAt('/agents/agent-vergabe')

    await user.click(await screen.findByRole('button', { name: 'Auftrag ändern' }))
    const field = screen.getByRole('textbox', { name: 'Auftrag' })
    await user.clear(field)
    await user.type(field, 'Prüfe streng.')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByText('Prüfe streng.')).toBeInTheDocument()
    expect(updates).toEqual([
      expect.objectContaining({
        instruction: 'Prüfe streng.',
        libraryIds: ['lib-1'],
        toolNames: ['search_knowledge', 'read_document'],
        planStep: 'CONFIRM',
      }),
    ])
  })

  it('nennt Bibliotheken, die man nicht lesen darf, nur als Zahl', async () => {
    renderAt('/agents/agent-vergabe/wissen')

    expect(await screen.findByText('Vergaberecht')).toBeInTheDocument()
    expect(screen.getByText(/Dazu 1 Bibliothek, die Sie nicht lesen dürfen/)).toBeInTheDocument()
  })

  it('zeigt Lesenden die Einrichtung ohne „Ändern“', async () => {
    const response = await fetch('/api/v1/agents/agent-recherche')
    const recherche = await response.json()
    server.use(
      http.get('/api/v1/agents/agent-recherche', () =>
        HttpResponse.json({ ...recherche, myRole: 'VIEWER' }),
      ),
    )
    renderAt('/agents/agent-recherche')

    expect(await screen.findByRole('heading', { level: 1, name: 'Recherche' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Auftrag ändern' })).not.toBeInTheDocument()
  })

  it('schaltet Agenten in der Administration aus und nimmt den Typ sofort aus dem Angebot', async () => {
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'admin@opaa.local',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    const user = userEvent.setup()
    renderAt('/admin/agents')

    const toggle = await screen.findByRole('switch', {
      name: 'Agenten in dieser Installation anbieten',
    })
    expect(toggle).toBeChecked()
    await user.click(toggle)
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(useAgentAvailability.getState().enabled).toBe(false))
    expect(mockAgentSettings.enabled).toBe(false)
  })
})
