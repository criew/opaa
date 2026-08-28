import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi, describe, expect, it } from 'vitest'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../../types/chat'
import type { OpenDocumentContentResult } from '../../utils/documentContent'

// #781 review, Wichtig 1: "Im Dokument öffnen" in the Fundstellen block (SourceFootnotes) shares
// MessageBubble's single `useDocumentPreview()` instance with the Belegfenster
// (SourceEvidenceDrawer) - these tests exercise that wiring end-to-end (text-preview dialog,
// download snackbar) at the level where the hook actually lives, mirroring
// SourceEvidenceDrawer.test.tsx's own mock of this module.
const { mockOpenDocumentContent } = vi.hoisted(() => ({
  mockOpenDocumentContent: vi.fn<() => Promise<OpenDocumentContentResult>>(async () => ({
    kind: 'blob-preview',
  })),
}))
vi.mock('../../utils/documentContent', () => ({
  openDocumentContent: mockOpenDocumentContent,
}))

const citedSource = {
  fileName: 'test.md',
  relevanceScore: 0.9,
  matchCount: 1,
  indexedAt: '2025-01-15T10:30:00Z',
  cited: true,
  citationValid: true,
}

const uncitedSource = {
  fileName: 'other.pdf',
  relevanceScore: 0.7,
  matchCount: 1,
  indexedAt: null,
  cited: false,
  citationValid: true,
}

describe('MessageBubble', () => {
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

  it('renders assistant message with feedback buttons', () => {
    const msg: ChatMessage = {
      id: '2',
      role: 'assistant',
      content: 'Here is the answer',
      sources: [],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Here is the answer')).toBeInTheDocument()
    expect(screen.getByLabelText('Daumen hoch')).toBeInTheDocument()
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

  it('highlights every row of a combined footnote range on click (#590 Nachbesserung)', async () => {
    const user = userEvent.setup()
    const msg: ChatMessage = {
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
        },
        {
          fileName: 'zweite.md',
          relevanceScore: 0.8,
          matchCount: 1,
          cited: true,
          indexedAt: null,
          citationValid: true,
        },
      ],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)

    await user.click(screen.getByRole('link', { name: 'Fundstellen 1 bis 2' }))

    expect(screen.getByText('erste.md').closest('[data-testid="source-card"]')).toHaveAttribute(
      'data-highlighted',
      'true',
    )
    expect(screen.getByText('zweite.md').closest('[data-testid="source-card"]')).toHaveAttribute(
      'data-highlighted',
      'true',
    )
  })

  it('fades both rows of a range together - no row stays lit via the URL hash', async () => {
    vi.useFakeTimers()
    try {
      const msg: ChatMessage = {
        id: 'r3',
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
          },
          {
            fileName: 'zweite.md',
            relevanceScore: 0.8,
            matchCount: 1,
            cited: true,
            indexedAt: null,
            citationValid: true,
          },
        ],
        timestamp: new Date(),
      }
      render(<MessageBubble message={msg} />)

      fireEvent.click(screen.getByRole('link', { name: 'Fundstellen 1 bis 2' }))
      expect(document.querySelectorAll('[data-highlighted="true"]')).toHaveLength(2)

      act(() => {
        vi.advanceTimersByTime(3000)
      })

      expect(document.querySelectorAll('[data-highlighted="true"]')).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('unfolds the block when a clicked range covers a folded row (#590 Nachbesserung)', async () => {
    const user = userEvent.setup()
    const files = ['d1.md', 'd2.md', 'd3.md', 'd4.md', 'd5.md']
    const msg: ChatMessage = {
      id: 'r2',
      role: 'assistant',
      content: 'Beleg' + files.map((f, i) => `【source: k${i}#0 | ${f}】`).join('') + '.',
      sources: files.map((fileName) => ({
        fileName,
        relevanceScore: 0.9,
        matchCount: 1,
        cited: true,
        indexedAt: null,
        citationValid: true,
      })),
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.queryByText('d5.md')).not.toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Fundstellen 1 bis 5' }))

    expect(await screen.findByText('d5.md')).toBeVisible()
    expect(screen.getByText('d5.md').closest('[data-testid="source-card"]')).toHaveAttribute(
      'data-highlighted',
      'true',
    )
  })

  it('lists cited sources in the Fundstellen block (#590)', () => {
    const msg: ChatMessage = {
      id: '3',
      role: 'assistant',
      content: 'Answer【source: aa#0 | test.md】',
      sources: [citedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Fundstellen')).toBeInTheDocument()
    expect(screen.getByText('1 Stelle in 1 Dokument')).toBeInTheDocument()
    expect(screen.getByText('test.md')).toBeInTheDocument()
  })

  it('shows the Fundort next to a cited document (#667)', () => {
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
    expect(screen.getByTestId('source-location')).toHaveTextContent('S. 2–4 · Abschn. Fristsetzung')
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

  it('hides uncited sources behind collapsible section', () => {
    const msg: ChatMessage = {
      id: '6',
      role: 'assistant',
      content: 'Answer',
      sources: [citedSource, uncitedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('test.md')).toBeInTheDocument()
    expect(
      screen.getByText(/Weitere geprüfte, nicht zitierte Treffer \(1\) anzeigen/),
    ).toBeInTheDocument()
    expect(screen.queryByText('other.pdf')).not.toBeVisible()
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

  it('expands uncited sources on click', async () => {
    const user = userEvent.setup()
    const msg: ChatMessage = {
      id: '7',
      role: 'assistant',
      content: 'Answer',
      sources: [citedSource, uncitedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    await user.click(screen.getByText(/Weitere geprüfte, nicht zitierte Treffer \(1\) anzeigen/))
    expect(await screen.findByText('other.pdf')).toBeVisible()
  })

  // #781 review, Wichtig 1: before this fix, clicking "Im Dokument öffnen" in the Fundstellen
  // block called openDocumentContent and discarded the result - a text-preview outcome produced
  // neither a dialog nor a download, worse than main's silent download.
  describe('"Im Dokument öffnen" in the Fundstellen block (#781 review, Wichtig 1)', () => {
    const mdSource = {
      fileName: '001_personalausweis.md',
      relevanceScore: 0.9,
      matchCount: 1,
      indexedAt: null,
      cited: true,
      citationValid: true,
      documentId: 'doc-1',
      sourceType: 'UPLOAD' as const,
    }

    function messageWithMdSource(): ChatMessage {
      return {
        id: 'md-1',
        role: 'assistant',
        content: 'Beleg【source: doc-1#0 | 001_personalausweis.md】.',
        sources: [mdSource],
        timestamp: new Date(),
      }
    }

    it('opens a Markdown text preview dialog instead of silently discarding the result', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({
        kind: 'text-preview',
        fileName: '001_personalausweis.md',
        contentType: 'text/markdown',
        content: '# Personalausweis\n\nAusgestellt am 1. März.',
      })
      const user = userEvent.setup()
      render(<MessageBubble message={messageWithMdSource()} />)

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(await screen.findByRole('dialog')).toBeInTheDocument()
      // #1016: heading elements are normalised per rendered content (rank compression from h2);
      // the h5 LOOK of "#" survives as the Typography variant.
      const previewHeading = screen.getByText('Personalausweis').closest('h2')
      expect(previewHeading).toBeInTheDocument()
      expect(previewHeading).toHaveClass('MuiTypography-h5')
      expect(screen.getByText(/Ausgestellt am 1\. März\./)).toBeInTheDocument()
    })

    it('shows a snackbar with the file name when a DOCX download starts', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
      const user = userEvent.setup()
      const msg = messageWithMdSource()
      msg.sources = [{ ...mdSource, fileName: 'bescheid.docx' }]
      msg.content = 'Beleg【source: doc-1#0 | bescheid.docx】.'
      render(<MessageBubble message={msg} />)

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(await screen.findByText('bescheid.docx wird heruntergeladen')).toBeInTheDocument()
    })

    it('shows a German error message when opening the original fails (e.g. 404)', async () => {
      mockOpenDocumentContent.mockRejectedValueOnce(
        new Error('Das Originaldokument wurde nicht gefunden.'),
      )
      const user = userEvent.setup()
      render(<MessageBubble message={messageWithMdSource()} />)

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(
        await screen.findByText('Das Originaldokument wurde nicht gefunden.'),
      ).toBeInTheDocument()
    })
  })
})
