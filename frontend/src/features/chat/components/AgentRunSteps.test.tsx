import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { AgentRun } from '@/shared/api/types'
import AgentRunSteps from './AgentRunSteps'

const RUN: AgentRun = {
  runId: '00000000-0000-0000-0000-000000000001',
  endState: 'COMPLETED',
  steps: [
    { tool: 'search_knowledge', detail: 'Reisekosten Inland', hits: 4, status: 'DONE' },
    { tool: 'search_knowledge', detail: 'Tagegeld', hits: 0, status: 'EMPTY' },
  ],
}

describe('AgentRunSteps', () => {
  it('lists the steps only after expanding', async () => {
    render(<AgentRunSteps run={RUN} />)
    const toggle = screen.getByRole('button', { name: /2 Schritte/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()

    await userEvent.click(toggle)

    const items = screen.getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Wissen durchsucht: Reisekosten Inland · 4 Treffer')
    expect(items[1]).toHaveTextContent('Tagegeld · 0 Treffer · nichts gefunden')
    expect(screen.queryByText('nicht geprüft')).not.toBeInTheDocument()
    expect(
      screen.getByText(/Laufkennung für die Fehlersuche: 00000000-0000-0000-0000-000000000001/),
    ).toBeInTheDocument()
  })

  it('marks a run that stopped at a limit as not checked', () => {
    render(<AgentRunSteps run={{ ...RUN, endState: 'LIMIT_CALLS' }} />)
    expect(screen.getByText('nicht geprüft')).toBeInTheDocument()
    expect(screen.getByText(/Höchstzahl an Schritten/)).toBeInTheDocument()
  })

  it('names a filled template with its files', async () => {
    render(
      <AgentRunSteps
        run={{
          ...RUN,
          steps: [
            {
              tool: 'fill_template',
              detail: 'Vermerk – Vermerk.docx, Vermerk.pdf',
              hits: null,
              status: 'DONE',
            },
          ],
        }}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: /1 Schritt/ }))
    expect(screen.getByRole('listitem')).toHaveTextContent(
      'Vorlage befüllt: Vermerk – Vermerk.docx, Vermerk.pdf',
    )
  })

  it('marks a run that asked back as a question, not as unchecked', () => {
    render(<AgentRunSteps run={{ ...RUN, endState: 'ASK_USER' }} />)
    expect(screen.getByText('Rückfrage')).toBeInTheDocument()
    expect(screen.queryByText('nicht geprüft')).not.toBeInTheDocument()
  })
})
