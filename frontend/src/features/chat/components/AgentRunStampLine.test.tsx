import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { AgentRun } from '@/shared/api/types'
import { renderWithProviders } from '@/test/test-utils'
import AgentRunStampLine from './AgentRunStampLine'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../types/chat'

const RUN: AgentRun = {
  runId: '00000000-0000-0000-0000-000000000001',
  endState: 'COMPLETED',
  steps: [],
  agentId: '00000000-0000-0000-0000-0000000000a1',
  agentName: 'Prüfhilfe',
  stamp: {
    agentRevision: 3,
    currentAgentRevision: 3,
    libraries: [
      { id: '00000000-0000-0000-0000-0000000000b1', name: 'Erlasse' },
      { id: '00000000-0000-0000-0000-0000000000b2', name: 'Satzungen' },
    ],
    templates: [{ id: '00000000-0000-0000-0000-0000000000c1', name: 'Bescheid', revision: 2 }],
    model: 'modell-a',
  },
}

describe('AgentRunStampLine', () => {
  it('marks the answer as a draft and names the revision, details on demand', async () => {
    renderWithProviders(<AgentRunStampLine run={RUN} />)

    expect(screen.getByRole('img', { name: 'Entwurf, nicht geprüft' })).toHaveTextContent('Entwurf')
    const toggle = screen.getByRole('button', { name: /Fassung 3/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // the agent is named once, at the question; the stamp line does not repeat it
    expect(screen.queryByText(/Prüfhilfe/)).not.toBeInTheDocument()
    expect(screen.queryByText('Erlasse, Satzungen')).not.toBeInTheDocument()

    await userEvent.click(toggle)

    expect(screen.getByText('Erlasse, Satzungen')).toBeInTheDocument()
    expect(screen.getByText('„Bescheid“, Fassung 2')).toBeInTheDocument()
    expect(screen.getByText('modell-a')).toBeInTheDocument()
  })

  it('says with which revision the answer was made once the agent has changed', () => {
    renderWithProviders(
      <AgentRunStampLine run={{ ...RUN, stamp: { ...RUN.stamp!, currentAgentRevision: 5 } }} />,
    )
    expect(
      screen.getByRole('button', { name: /mit Fassung 3 erstellt, aktuell Fassung 5/ }),
    ).toBeInTheDocument()
  })

  it('shows the draft mark alone for a run kept without a stamp', () => {
    renderWithProviders(<AgentRunStampLine run={{ ...RUN, stamp: null }} />)
    expect(screen.getByRole('img', { name: 'Entwurf, nicht geprüft' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('leaves an ordinary answer without draft mark and stamp', () => {
    const message: ChatMessage = {
      id: 'm1',
      role: 'assistant',
      content: 'Gewöhnliche Antwort.',
      timestamp: new Date('2026-11-10T08:00:00Z'),
    }
    renderWithProviders(<MessageBubble message={message} />)
    expect(screen.queryByTestId('agent-stamp')).not.toBeInTheDocument()
    expect(screen.queryByText('Entwurf')).not.toBeInTheDocument()
  })
})
