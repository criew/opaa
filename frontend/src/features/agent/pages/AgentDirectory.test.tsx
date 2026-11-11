import { beforeEach, describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { mockMyCapabilities } from '@/features/capability/mocks/capabilityFixtures'
import { useAgentDirectoryStore } from '../stores/agentDirectoryStore'
import { mockAgentSettings } from '../mocks/agentFixtures'
import AgentDirectoryPage from './AgentDirectoryPage'
import AgentDirectoryDetailPage from './AgentDirectoryDetailPage'

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/agent-directory" element={<AgentDirectoryPage />} />
      <Route path="/agent-directory/agents/:agentId" element={<AgentDirectoryDetailPage />} />
      <Route path="/agent-directory/templates/:templateId" element={<AgentDirectoryDetailPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

/** Counts the reads of the directory: each one is an entry in the audit log. */
function countDirectoryReads(): { reads: number } {
  const counter = { reads: 0 }
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname === '/api/v1/agent-directory') counter.reads += 1
  })
  return counter
}

describe('Agentenverzeichnis', () => {
  beforeEach(() => {
    setMockAuthState()
    useAgentDirectoryStore.getState().reset()
    mockAgentSettings.enabled = true
    server.events.removeAllListeners()
  })

  it('lists every agent with count and export and opens the read-only detail without a second read', async () => {
    const counter = countDirectoryReads()
    const user = userEvent.setup()
    renderAt('/agent-directory')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Agentenverzeichnis' }),
    ).toBeVisible()
    expect(screen.getByText(/2 Agenten/)).toBeVisible()
    expect(screen.getByRole('button', { name: 'Als CSV exportieren' })).toBeVisible()
    expect(screen.getByRole('table', { name: 'Agenten' })).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Vergabeprüfung' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Vergabeprüfung' })).toBeVisible()
    expect(screen.getByText('Nenne jede Abweichung mit Fundstelle und Paragraf.')).toBeVisible()
    expect(screen.getByText('Vergaberecht')).toBeVisible()
    expect(screen.getByText('Anweisung')).toBeVisible()
    expect(screen.queryByRole('button', { name: /Speichern|Bearbeiten|Sperren/ })).toBeNull()
    expect(counter.reads).toBe(1)
  })

  it('shows neither owner nor usage', async () => {
    renderAt('/agent-directory')

    const table = await screen.findByRole('table', { name: 'Agenten' })
    expect(within(table).queryByText(/Eigentümer|Nutzung|genutzt|Aufrufe/i)).toBeNull()
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((cell) => cell.textContent),
    ).toEqual(['Name', 'Zweck', 'Verantwortlich', 'Fassung', 'Gesperrt seit'])
  })

  it('explains the missing right instead of showing the directory', async () => {
    const original = [...mockMyCapabilities.capabilities]
    mockMyCapabilities.capabilities = original.filter((c) => c !== 'VIEW_AGENT_DIRECTORY')
    try {
      renderAt('/agent-directory')

      expect(
        await screen.findByText(/Das Agentenverzeichnis sehen nur Personen mit dem Recht/),
      ).toBeVisible()
      expect(screen.queryByRole('table')).toBeNull()
      expect(screen.queryByRole('button', { name: 'Als CSV exportieren' })).toBeNull()
    } finally {
      mockMyCapabilities.capabilities = original
    }
  })

  it('says so while agents are switched off', async () => {
    mockAgentSettings.enabled = false
    try {
      renderAt('/agent-directory')

      expect(
        await screen.findByText(/Agenten sind in dieser Installation ausgeschaltet/),
      ).toBeVisible()
    } finally {
      mockAgentSettings.enabled = true
    }
  })
})
