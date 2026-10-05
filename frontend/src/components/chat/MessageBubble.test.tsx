import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi, describe, expect, it } from 'vitest'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../../types/chat'
import { useNotificationStore } from '../../stores/notificationStore'
import { useCopyAnnouncement } from './copyAnnouncer'

const citedSource = {
  fileName: 'test.md',
  relevanceScore: 0.9,
  matchCount: 1,
  indexedAt: '2025-01-15T10:30:00Z',
  cited: true,
  citationValid: true,
  privateSource: false,
}

const uncitedSource = {
  fileName: 'other.pdf',
  relevanceScore: 0.7,
  matchCount: 1,
  indexedAt: null,
  cited: false,
  citationValid: true,
  privateSource: false,
}

describe('MessageBubble', () => {
  it('names the prompt a question was built from, as plain text without a link', () => {
    render(
      <MessageBubble
        message={{
          id: '1',
          role: 'user',
          content: 'Fasse den Stand zum 24.09.2026 zusammen.',
          usedPromptTitle: 'Zusammenfassung',
          timestamp: new Date(),
        }}
      />,
    )

    expect(screen.getByTestId('used-prompt')).toHaveTextContent('Prompt: Zusammenfassung')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('says under an answer that private sources were in its context, and only then', () => {
    const { rerender } = render(
      <MessageBubble
        message={{
          id: '1',
          role: 'assistant',
          content: 'Antwort ohne Fußnote.',
          sources: [{ ...uncitedSource, privateSource: true }],
          privateSourcesInContext: true,
          timestamp: new Date(),
        }}
      />,
    )

    expect(screen.getByTestId('private-sources-in-context')).toHaveTextContent(
      'Private Quellen im Kontext',
    )

    rerender(
      <MessageBubble
        message={{
          id: '1',
          role: 'assistant',
          content: 'Antwort ohne Fußnote.',
          sources: [uncitedSource],
          timestamp: new Date(),
        }}
      />,
    )
    expect(screen.queryByTestId('private-sources-in-context')).not.toBeInTheDocument()
  })

  it('shows no prompt hint at a question without one', () => {
    render(
      <MessageBubble
        message={{ id: '1', role: 'user', content: 'Frage', timestamp: new Date() }}
      />,
    )

    expect(screen.queryByTestId('used-prompt')).not.toBeInTheDocument()
  })

  it('renders user message content', () => {
    const msg: ChatMessage = {
      id: '1',
      role: 'user',
      content: 'Hello there',
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Hello there')).toBeInTheDocument()
  })

  it('renders assistant message', () => {
    const msg: ChatMessage = {
      id: '2',
      role: 'assistant',
      content: 'Here is the answer',
      sources: [],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Here is the answer')).toBeInTheDocument()
    // regression guard for #1447: no rating control is rendered for assistant messages
    expect(screen.queryByLabelText('Daumen hoch')).not.toBeInTheDocument()
  })

  it('renders assistant message with markdown', () => {
    const msg: ChatMessage = {
      id: '4',
      role: 'assistant',
      content: 'This is **bold** text',
      sources: [],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    const bold = screen.getByText('bold')
    expect(bold.tagName).toBe('STRONG')
  })

  it('renders user message as plain text without markdown parsing', () => {
    const msg: ChatMessage = {
      id: '5',
      role: 'user',
      content: 'This is **not bold**',
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('This is **not bold**')).toBeInTheDocument()
    expect(screen.queryByText('not bold')?.tagName).not.toBe('STRONG')
  })

  function twoCitedMessage(): ChatMessage {
    return {
      id: 'r1',
      role: 'assistant',
      content: 'Beleg【source: a#0 | erste.md】【source: b#0 | zweite.md】.',
      sources: [
        {
          fileName: 'erste.md',
          relevanceScore: 0.9,
          matchCount: 1,
          cited: true,
          indexedAt: null,
          citationValid: true,
          privateSource: false,
        },
        {
          fileName: 'zweite.md',
          relevanceScore: 0.8,
          matchCount: 1,
          cited: true,
          indexedAt: null,
          citationValid: true,
          privateSource: false,
        },
        { ...uncitedSource, fileName: 'dritte.md' },
      ],
      timestamp: new Date(),
    }
  }

  function evidenceRow(fileName: string): HTMLElement {
    const drawer = screen.getByRole('dialog', { name: 'Belege dieser Antwort' })
    const row = within(drawer)
      .getAllByTestId('evidence-doc')
      .find((el) => el.getAttribute('data-file') === fileName)
    if (!row) throw new Error(`no Beleg row for ${fileName}`)
    return row
  }

  it('shows no source list under the answer, only "Belege anzeigen" with a count line', () => {
    render(<MessageBubble message={twoCitedMessage()} />)

    expect(screen.queryByText('erste.md')).not.toBeInTheDocument()
    expect(screen.queryByText('dritte.md')).not.toBeInTheDocument()
    expect(screen.queryByText('Fundstellen')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Belege anzeigen' })).toBeInTheDocument()
    expect(screen.getByTestId('evidence-summary')).toHaveTextContent(
      '2 Stellen in 2 Dokumenten · 1 weitere geprüft',
    )
  })

  it('carries the footnote numbers as marks, folding the rest into "+n"', () => {
    const files = ['d1.md', 'd2.md', 'd3.md', 'd4.md', 'd5.md']
    const msg: ChatMessage = {
      id: 'marks',
      role: 'assistant',
      content: 'Beleg' + files.map((f, i) => `【source: k${i}#0 | ${f}】`).join('') + '.',
      sources: files.map((fileName) => ({ ...citedSource, fileName })),
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)

    const button = screen.getByRole('button', { name: 'Belege anzeigen' })
    expect(button).toHaveTextContent(/^123\+2Belege anzeigen$/)
    expect(button).toHaveAccessibleDescription('5 Stellen in 5 Dokumenten')
  })

  it('counts checked sources when the answer cites none', () => {
    const msg: ChatMessage = {
      id: '6',
      role: 'assistant',
      content: 'Answer',
      sources: [uncitedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByTestId('evidence-summary')).toHaveTextContent('1 geprüft, keine zitiert')
  })

  it('offers no "Belege anzeigen" for an answer without sources', () => {
    const msg: ChatMessage = {
      id: '2b',
      role: 'assistant',
      content: 'Here is the answer',
      sources: [],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.queryByRole('button', { name: 'Belege anzeigen' })).not.toBeInTheDocument()
  })

  it('opens every Beleg, cited and uncited, without a mark via "Belege anzeigen"', async () => {
    const user = userEvent.setup()
    render(<MessageBubble message={twoCitedMessage()} />)

    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))

    expect(evidenceRow('erste.md')).not.toHaveAttribute('data-focused')
    expect(evidenceRow('zweite.md')).not.toHaveAttribute('data-focused')
    expect(evidenceRow('dritte.md')).toHaveAttribute('data-cited', 'false')
  })

  it('opens the Belegfenster at every Beleg of a clicked footnote range', async () => {
    const user = userEvent.setup()
    render(<MessageBubble message={twoCitedMessage()} />)

    await user.click(screen.getByRole('button', { name: 'Fundstellen 1 bis 2' }))

    expect(evidenceRow('erste.md')).toHaveAttribute('data-focused', 'true')
    expect(evidenceRow('zweite.md')).toHaveAttribute('data-focused', 'true')
    expect(evidenceRow('dritte.md')).not.toHaveAttribute('data-focused')
  })

  it('marks only the Beleg of a single footnote and scrolls it into view', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    try {
      const user = userEvent.setup()
      const msg = twoCitedMessage()
      msg.content = 'Erst【source: a#0 | erste.md】, dann【source: b#0 | zweite.md】.'
      render(<MessageBubble message={msg} />)

      await user.click(screen.getByRole('button', { name: 'Fundstelle 2: zweite.md' }))

      expect(evidenceRow('zweite.md')).toHaveAttribute('data-focused', 'true')
      expect(evidenceRow('erste.md')).not.toHaveAttribute('data-focused')
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())
      expect(scrollIntoView.mock.contexts[0]).toBe(evidenceRow('zweite.md'))
      expect(evidenceRow('zweite.md')).toHaveFocus()
    } finally {
      // @ts-expect-error jsdom has no scrollIntoView; restore that state
      delete Element.prototype.scrollIntoView
    }
  })

  it('shows the Fundort of a cited document in the Belegfenster (#667)', async () => {
    const user = userEvent.setup()
    const msg: ChatMessage = {
      id: '30',
      role: 'assistant',
      content: 'Answer【source: aa#2 | test.md】',
      sources: [
        {
          ...citedSource,
          documentId: 'aa',
          chunkLocations: [{ chunkIndex: 2, location: 'S. 2–4 · Abschn. Fristsetzung' }],
        },
      ],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    expect(within(evidenceRow('test.md')).getByTestId('source-location')).toHaveTextContent(
      'S. 2–4 · Abschn. Fristsetzung',
    )
  })

  it('names the searched libraries under an answer that cites nothing (#667)', () => {
    const msg: ChatMessage = {
      id: '31',
      role: 'assistant',
      content: 'Dazu lässt sich in den Beständen dieses Space nichts belegen.',
      sources: [],
      searchedLibraries: [
        { id: '1', name: 'Dienstanweisungen' },
        { id: '2', name: 'Formulare' },
      ],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByTestId('searched-libraries')).toHaveTextContent(
      'Durchsucht wurden: Dienstanweisungen, Formulare',
    )
  })

  it('omits the searched libraries once the answer carries Fundstellen (#667)', () => {
    const msg: ChatMessage = {
      id: '32',
      role: 'assistant',
      content: 'Answer【source: aa#0 | test.md】',
      sources: [citedSource],
      searchedLibraries: [{ id: '1', name: 'Dienstanweisungen' }],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.queryByTestId('searched-libraries')).not.toBeInTheDocument()
  })

  it('shows a hint when the answer was generated without knowledge', () => {
    const msg: ChatMessage = {
      id: '8',
      role: 'assistant',
      content: 'Answer',
      sources: [],
      answeredWithoutKnowledge: true,
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Diese Antwort wurde ohne Wissensbasis erstellt.')).toBeInTheDocument()
  })

  // A space without associated knowledge never answers as if sourced: the bubble says why.
  it('names the two space signals separately', () => {
    const base: ChatMessage = {
      id: '8b',
      role: 'assistant',
      content: 'Answer',
      sources: [],
      timestamp: new Date(),
    }
    const { unmount } = render(
      <MessageBubble message={{ ...base, noKnowledgeAssignedToSpace: true }} />,
    )
    expect(
      screen.getByText(
        'Diesem Space ist kein Wissen zugeordnet. Diese Antwort stützt sich auf keine Dokumente.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText(/derzeit kein Wissen verfügbar/)).not.toBeInTheDocument()
    unmount()

    render(<MessageBubble message={{ ...base, noKnowledgeAvailableInSpace: true }} />)
    expect(
      screen.getByText('In diesem Space ist für Sie derzeit kein Wissen verfügbar.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/kein Wissen zugeordnet/)).not.toBeInTheDocument()
  })

  it('does not show the hint when the answer used the knowledge base', () => {
    const msg: ChatMessage = {
      id: '9',
      role: 'assistant',
      content: 'Answer',
      sources: [citedSource],
      answeredWithoutKnowledge: false,
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(
      screen.queryByText('Diese Antwort wurde ohne Wissensbasis erstellt.'),
    ).not.toBeInTheDocument()
  })
})

describe('MessageBubble copy', () => {
  const answer: ChatMessage = {
    id: 'copy-a',
    role: 'assistant',
    content: 'Er kostet **42,60 Euro** 【source: doc-a#0 | 001_personalausweis.md】.',
    sources: [{ ...citedSource, fileName: '001_personalausweis.md', documentId: 'doc-a' }],
    timestamp: new Date(),
  }

  it('copies the answer as Markdown without footnote marks and confirms it at the button', async () => {
    const user = userEvent.setup()
    render(<MessageBubble message={answer} />)

    await user.click(screen.getByRole('button', { name: 'Antwort kopieren' }))

    expect(await navigator.clipboard.readText()).toBe('Er kostet **42,60 Euro**.')
    expect(screen.getByRole('button', { name: 'Antwort kopiert' })).toHaveTextContent('Kopiert')
    expect(useCopyAnnouncement.getState().text).toBe('Die Antwort wurde kopiert.')
  })

  it('offers the answer with its sources as Markdown footnotes', async () => {
    const user = userEvent.setup()
    render(<MessageBubble message={answer} />)

    await user.click(screen.getByRole('button', { name: 'Weitere Kopieroptionen' }))
    await user.click(screen.getByRole('menuitem', { name: /Mit Quellenangaben/ }))

    expect(await navigator.clipboard.readText()).toBe(
      'Er kostet **42,60 Euro**[^1].\n\n[^1]: 001_personalausweis.md',
    )
  })

  it('offers the answer as plain text', async () => {
    const user = userEvent.setup()
    render(<MessageBubble message={answer} />)

    await user.click(screen.getByRole('button', { name: 'Weitere Kopieroptionen' }))
    await user.click(screen.getByRole('menuitem', { name: /Nur Text/ }))

    expect(await navigator.clipboard.readText()).toBe('Er kostet 42,60 Euro.')
  })

  it('copies by keyboard alone', async () => {
    const user = userEvent.setup()
    render(<MessageBubble message={answer} />)

    screen.getByRole('button', { name: 'Antwort kopieren' }).focus()
    await user.keyboard('{Enter}')

    expect(await navigator.clipboard.readText()).toBe('Er kostet **42,60 Euro**.')
  })

  it('offers copying for an answer without any Belege too', () => {
    render(
      <MessageBubble
        message={{
          id: 'copy-b',
          role: 'assistant',
          content: 'Nichts gefunden.',
          timestamp: new Date(),
        }}
      />,
    )
    expect(screen.getByRole('button', { name: 'Antwort kopieren' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Belege anzeigen' })).not.toBeInTheDocument()
  })

  it('copies one of the own questions as plain text', async () => {
    const user = userEvent.setup()
    render(
      <MessageBubble
        message={{
          id: 'copy-q',
          role: 'user',
          content: 'Was kostet **ein** Ausweis?',
          timestamp: new Date(),
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Frage kopieren' }))

    expect(await navigator.clipboard.readText()).toBe('Was kostet **ein** Ausweis?')
    expect(screen.getByRole('button', { name: 'Frage kopiert' })).toBeInTheDocument()
  })

  it('says so when the clipboard refuses', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('denied'))
    useNotificationStore.getState().reset()
    render(<MessageBubble message={answer} />)

    await user.click(screen.getByRole('button', { name: 'Antwort kopieren' }))

    await waitFor(() =>
      expect(useNotificationStore.getState().queue.at(-1)?.message).toBe(
        'Die Antwort konnte nicht kopiert werden – bitte manuell markieren.',
      ),
    )
    expect(screen.getByRole('button', { name: 'Antwort kopieren' })).toBeInTheDocument()
  })
})
