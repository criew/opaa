import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../../types/chat'
import type { SourceReference } from '../../types/api'

function source(
  fileName: string,
  cited: boolean,
  relevanceScore: number,
  spaceName = 'Engineering',
  citationValid: boolean | null = true,
): SourceReference {
  return {
    fileName,
    spaceName,
    relevanceScore,
    matchCount: 1,
    cited,
    indexedAt: null,
    citationValid,
  }
}

/** An answer citing three documents, plus one checked-but-uncited source. */
function message(): ChatMessage {
  return {
    id: 'ev-1',
    role: 'assistant',
    content:
      'Erstens【source: a#0 | schwach.md】, zweitens【source: b#0 | stark.md】, ' +
      'drittens【source: c#0 | mittel.md】.',
    sources: [
      source('schwach.md', true, 0.41),
      source('stark.md', true, 0.97),
      source('mittel.md', true, 0.7),
      source('ungenutzt.md', false, 0.3),
    ],
    timestamp: new Date('2026-08-20T14:12:00'),
  }
}

async function openDrawer() {
  const user = userEvent.setup()
  renderWithProviders(<MessageBubble message={message()} />)
  await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
  return { user, drawer: await screen.findByRole('dialog', { name: 'Belege dieser Antwort' }) }
}

describe('SourceEvidenceDrawer (#592, Mockup 1i)', () => {
  it('opens from the Fundstellen block with header, count line and answer timestamp', async () => {
    const { drawer } = await openDrawer()

    expect(within(drawer).getByText('Belege dieser Antwort')).toBeInTheDocument()
    expect(
      within(drawer).getByText('3 Stellen in 3 Dokumenten · nach Gewicht sortiert'),
    ).toBeInTheDocument()
    expect(within(drawer).getByText(/Stand der Antwort: 20\.08\.2026, 14:12/)).toBeInTheDocument()
  })

  it('sorts documents by relevance, uncited ones greyed at the end', async () => {
    const { drawer } = await openDrawer()

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['stark.md', 'mittel.md', 'schwach.md', 'ungenutzt.md'])
  })

  it('filters by the search field', async () => {
    const { user, drawer } = await openDrawer()

    await user.type(within(drawer).getByPlaceholderText('In Belegen suchen …'), 'stark')

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['stark.md'])
  })

  it('hides checked-but-uncited sources behind the "Nur zitierte" filter', async () => {
    const { user, drawer } = await openDrawer()

    await user.click(within(drawer).getByRole('button', { name: 'Nur zitierte' }))

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['stark.md', 'mittel.md', 'schwach.md'])
  })

  it('flags a source with an invalid citation as "Beleg nicht überprüfbar" (#386)', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-invalid',
          role: 'assistant',
          content: 'Beleg【source: a#0 | schwach.md】.',
          sources: [source('schwach.md', true, 0.41, 'Engineering', false)],
          timestamp: new Date('2026-08-21T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const doc = within(drawer).getByTestId('evidence-doc')
    expect(doc).toHaveAttribute('data-citation-valid', 'false')
    expect(within(doc).getByText('Beleg nicht überprüfbar')).toBeInTheDocument()
  })

  it('does not flag a validly cited source (#386)', async () => {
    const { drawer } = await openDrawer()

    const docs = within(drawer).getAllByTestId('evidence-doc')
    for (const doc of docs) {
      expect(doc).toHaveAttribute('data-citation-valid', 'true')
    }
    expect(within(drawer).queryByText('Beleg nicht überprüfbar')).not.toBeInTheDocument()
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const { user } = await openDrawer()

    await user.keyboard('{Escape}')

    // The drawer leaves with a transition - wait for the unmount instead of asserting mid-exit.
    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: 'Belege dieser Antwort' }),
      ).not.toBeInTheDocument(),
    )
    expect(
      screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }),
    ).toHaveFocus()
  })
})
