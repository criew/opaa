import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../../types/chat'
import type { SourceReference } from '../../types/api'
import type { OpenDocumentContentResult } from '../../utils/documentContent'

// #739/#780: the "Im Dokument öffnen" action delegates to this shared module (see its own tests for
// the Blob-fetch/preview-vs-download behaviour, and LibraryDetailPage.test.tsx for #738's original
// wiring of this mock pattern).
const { mockOpenDocumentContent } = vi.hoisted(() => ({
  mockOpenDocumentContent: vi.fn<() => Promise<OpenDocumentContentResult>>(async () => ({
    kind: 'blob-preview',
  })),
}))
vi.mock('../../utils/documentContent', () => ({
  openDocumentContent: mockOpenDocumentContent,
}))

function source(
  fileName: string,
  cited: boolean,
  relevanceScore: number,
  spaceName = 'Engineering',
  citationValid: boolean | null = true,
  extra: Partial<SourceReference> = {},
): SourceReference {
  return {
    fileName,
    spaceName,
    relevanceScore,
    matchCount: 1,
    cited,
    indexedAt: null,
    citationValid,
    ...extra,
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
  beforeEach(() => {
    vi.clearAllMocks()
  })

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

  it('flags a source with an invalid citation as "Beleg nicht bestätigt" (#386)', async () => {
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
    expect(within(doc).getByText('Beleg nicht bestätigt')).toBeInTheDocument()
  })

  it('does not flag a validly cited source (#386)', async () => {
    const { drawer } = await openDrawer()

    const docs = within(drawer).getAllByTestId('evidence-doc')
    for (const doc of docs) {
      expect(doc).toHaveAttribute('data-citation-valid', 'true')
    }
    expect(within(drawer).queryByText('Beleg nicht bestätigt')).not.toBeInTheDocument()
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

  describe('"Im Dokument öffnen" (#739)', () => {
    async function openDrawerWith(source: SourceReference) {
      const user = userEvent.setup()
      renderWithProviders(
        <MessageBubble
          message={{
            id: 'ev-open',
            role: 'assistant',
            content: 'Beleg【source: a#0 | doc.pdf】.',
            sources: [source],
            timestamp: new Date('2026-08-21T09:00:00'),
          }}
        />,
      )
      await user.click(
        screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }),
      )
      const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })
      return { user, drawer }
    }

    it('fetches and opens a local original (UPLOAD/FILESYSTEM) via the download endpoint', async () => {
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, 'Engineering', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'doc.pdf')
    })

    it('opens an HTTP_DIRECTORY document through the content endpoint too, keeping sourceUrl as a secondary "Quelle" link (#747)', async () => {
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, 'Engineering', true, {
          documentId: 'doc-1',
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://example.gov/verzeichnis/doc.pdf',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'doc.pdf')

      const sourceLink = within(drawer).getByRole('link', {
        name: 'Quelle: https://example.gov/verzeichnis/doc.pdf',
      })
      expect(sourceLink).toHaveAttribute('href', 'https://example.gov/verzeichnis/doc.pdf')
      expect(sourceLink).toHaveAttribute('target', '_blank')
      expect(sourceLink).toHaveAttribute('rel', expect.stringContaining('noopener'))
    })

    it('hides the action for a synthetic entry with no documentId at all', async () => {
      const { drawer } = await openDrawerWith(source('doc.pdf', true, 0.9))

      expect(
        within(drawer).queryByRole('button', { name: 'Im Dokument öffnen' }),
      ).not.toBeInTheDocument()
      expect(within(drawer).queryByRole('link', { name: 'Quelle' })).not.toBeInTheDocument()
    })

    it('shows a German error message when opening the original fails (e.g. 404)', async () => {
      mockOpenDocumentContent.mockRejectedValueOnce(
        new Error('Das Originaldokument wurde nicht gefunden.'),
      )
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, 'Engineering', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(
        await within(drawer).findByText('Das Originaldokument wurde nicht gefunden.'),
      ).toBeInTheDocument()
    })

    // #780: Markdown/plain text render in a client-side dialog instead of a silent download.
    it('opens a Markdown text preview dialog instead of a silent download (#780)', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({
        kind: 'text-preview',
        fileName: '001_personalausweis.md',
        contentType: 'text/markdown',
        content: '# Personalausweis\n\nAusgestellt am 1. März.',
      })
      const { user, drawer } = await openDrawerWith(
        source('001_personalausweis.md', true, 0.9, 'Engineering', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      // The preview dialog portals to document.body, outside the Belegfenster drawer, so it is
      // queried at the document level rather than scoped to `drawer`.
      expect(await screen.findByText('Personalausweis')).toBeInTheDocument()
      expect(screen.getByText('Personalausweis').closest('h5')).toBeInTheDocument()
      expect(screen.getByText(/Ausgestellt am 1\. März\./)).toBeInTheDocument()
    })

    // #780 acceptance criteria: every format without a preview (DOCX among them) must give visible
    // download feedback so the click never appears to do nothing.
    it('shows a snackbar with the file name when a DOCX download starts (#780)', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
      const { user, drawer } = await openDrawerWith(
        source('bescheid.docx', true, 0.9, 'Engineering', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(await screen.findByText('bescheid.docx wird heruntergeladen')).toBeInTheDocument()
    })
  })
})
