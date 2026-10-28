import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, waitForDialogClosed } from '../../../test/test-utils'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../types/chat'
import type { SourceReference } from '@/shared/api/types'
import type { OpenDocumentContentResult } from '@/features/library'

// The "Dokument öffnen" action delegates to this shared module (see its own tests for the
// Blob-fetch/preview-vs-download behaviour; LibraryDetailPage.test.tsx uses the same mock pattern).
const { mockOpenDocumentContent } = vi.hoisted(() => ({
  mockOpenDocumentContent: vi.fn<() => Promise<OpenDocumentContentResult>>(async () => ({
    kind: 'blob-preview',
  })),
}))
vi.mock('../../library/utils/documentContent', () => ({
  openDocumentContent: mockOpenDocumentContent,
}))

function source(
  fileName: string,
  cited: boolean,
  relevanceScore: number,
  citationValid: boolean | null = true,
  extra: Partial<SourceReference> = {},
): SourceReference {
  return {
    fileName,
    relevanceScore,
    matchCount: 1,
    cited,
    indexedAt: null,
    citationValid,
    privateSource: false,
    ...extra,
  }
}

/**
 * An answer citing three documents, plus one checked-but-uncited source. `sources` arrives
 * in the order the retrieval pipeline settled on, each `relevanceScore` the reciprocal of that
 * position - deliberately a different order than the citation markers in the text.
 */
function message(): ChatMessage {
  return {
    id: 'ev-1',
    role: 'assistant',
    content:
      'Erstens【source: a#0 | zweiter.md】, zweitens【source: b#0 | erster.md】, ' +
      'drittens【source: c#0 | dritter.md】.',
    sources: [
      source('erster.md', true, 1),
      source('zweiter.md', true, 0.5),
      source('dritter.md', true, 1 / 3),
      source('ungenutzt.md', false, 0.25),
    ],
    timestamp: new Date('2026-08-20T14:12:00'),
  }
}

async function openDrawer() {
  const user = userEvent.setup()
  renderWithProviders(<MessageBubble message={message()} />)
  await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
  return { user, drawer: await screen.findByRole('dialog', { name: 'Belege dieser Antwort' }) }
}

function fileNames(drawer: HTMLElement) {
  return within(drawer)
    .getAllByTestId('evidence-doc')
    .map((el) => el.getAttribute('data-file'))
}

describe('SourceEvidenceDrawer (#592, Mockup 1i)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('opens from "Belege anzeigen" with the bare header and the answer timestamp (#3259)', async () => {
    const { drawer } = await openDrawer()

    expect(within(drawer).getByText('Belege dieser Antwort')).toBeInTheDocument()
    expect(within(drawer).queryByText(/Zitatnummer|Relevanz|Stellen in/)).not.toBeInTheDocument()
    expect(within(drawer).getByRole('textbox', { name: 'In Belegen suchen' })).toBeVisible()
    expect(within(drawer).getByText(/Stand der Antwort: 20\.08\.2026, 14:12/)).toBeInTheDocument()
  })

  // `message()` deliberately cites in a different order than the pipeline's `sources`
  // array (zweiter.md is footnote 1 despite sitting second in `sources`) - the cited group must
  // follow the footnote numbers, not the pipeline order, with the uncited source trailing.
  it('lists cited documents by citation number, uncited ones after them once shown', async () => {
    const { user, drawer } = await openDrawer()

    expect(fileNames(drawer)).toEqual(['zweiter.md', 'erster.md', 'dritter.md'])

    await user.click(within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' }))

    expect(fileNames(drawer)).toEqual(['zweiter.md', 'erster.md', 'dritter.md', 'ungenutzt.md'])
  })

  it('opens with the cited Belege only and unfolds the uncited ones behind a link (#3259)', async () => {
    const { user, drawer } = await openDrawer()

    expect(within(drawer).queryByRole('button', { name: 'Nur zitierte' })).not.toBeInTheDocument()
    const toggle = within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', 'belege-ev-1')
    expect(document.getElementById('belege-ev-1')).toContainElement(
      within(drawer).getAllByTestId('evidence-doc')[0],
    )
    expect(
      within(drawer)
        .getAllByTestId('evidence-doc')
        .every((el) => el.getAttribute('data-cited') === 'true'),
    ).toBe(true)

    await user.click(toggle)

    const collapse = within(drawer).getByRole('button', { name: 'Nicht zitierte ausblenden' })
    expect(collapse).toHaveAttribute('aria-expanded', 'true')
    expect(within(drawer).getByText('geprüft, nicht zitiert')).toBeVisible()

    await user.click(collapse)

    expect(fileNames(drawer)).toEqual(['zweiter.md', 'erster.md', 'dritter.md'])
    expect(within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('offers no link when every source is cited', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-all-cited',
          role: 'assistant',
          content: 'Beleg【source: a#0 | a.md】.',
          sources: [source('a.md', true, 1)],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    expect(within(drawer).queryByRole('button', { name: /Nicht zitierte/ })).not.toBeInTheDocument()
  })

  it('says so when an answer cites nothing, and still unfolds the checked sources', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-none-cited',
          role: 'assistant',
          content: 'Dazu steht nichts in den Unterlagen.',
          sources: [source('geprueft.md', false, 1)],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    expect(within(drawer).getByText('Diese Antwort zitiert keinen Beleg.')).toBeVisible()
    expect(within(drawer).queryByTestId('evidence-doc')).not.toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' }))

    expect(fileNames(drawer)).toEqual(['geprueft.md'])
  })

  // Regression guard for the reported demo behaviour - footnote 2 points at the lowest-rank
  // source and footnote 3 at a higher-ranked one, so citation-number order and rank order disagree
  // on which of the two comes first.
  it('keeps citation-number order even when a later footnote outranks an earlier one', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-1238',
          role: 'assistant',
          content:
            'Erstens【source: a#0 | eins.md】, zweitens【source: b#0 | zwei.md】, ' +
            'drittens【source: c#0 | drei.md】.',
          sources: [
            source('eins.md', true, 1),
            source('drei.md', true, 0.5),
            source('zwei.md', true, 1 / 3),
          ],
          timestamp: new Date('2026-09-04T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const rows = within(drawer).getAllByTestId('evidence-doc')
    expect(rows.map((el) => el.getAttribute('data-file'))).toEqual([
      'eins.md',
      'zwei.md',
      'drei.md',
    ])
    expect(within(rows[0]).getByText('1')).toBeInTheDocument()
    expect(within(rows[1]).getByText('2')).toBeInTheDocument()
    expect(within(rows[2]).getByText('3')).toBeInTheDocument()
  })

  // relevanceScore is the reciprocal of the fused rank, so it is only ever a label - a
  // legacy persisted message still carries a path-dependent raw score, and the
  // drawer must not reorder by it. A lexical-only source (tiny ts_rank) that the pipeline put
  // first therefore stays first.
  it('never reorders by relevanceScore, even when the values contradict the backend order', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-legacy',
          role: 'assistant',
          content: 'Beleg【source: a#0 | lexikalisch.md】 und【source: b#0 | vektor.md】.',
          sources: [source('lexikalisch.md', true, 0.09), source('vektor.md', true, 0.8)],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['lexikalisch.md', 'vektor.md'])
  })

  // Specification „Konnektor-Freigabe und Sperre“: a source whose library is no longer
  // updated carries „Stand vom …“ with the last successful run (not indexedAt),
  // the reason and the responsible party the backend names; others do not.
  it('marks a source that is not updated with "Stand vom", the reason and who is in charge', async () => {
    const user = userEvent.setup()
    const frozen: ChatMessage = {
      ...message(),
      sources: [
        source('erster.md', true, 1, true, {
          indexedAt: '2025-01-10T08:00:00Z',
          freeze: {
            block: { reason: 'NOT_CONNECTED', responsible: 'Verwaltende der Bibliothek' },
            asOf: '2026-03-12T10:00:00Z',
          },
        }),
        source('zweiter.md', true, 0.5),
        source('dritter.md', true, 1 / 3),
      ],
    }
    renderWithProviders(<MessageBubble message={frozen} />)
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const notices = within(drawer).getAllByTestId('source-frozen')
    expect(notices).toHaveLength(1)
    expect(notices[0]).toHaveTextContent(
      /Stand vom 12\.03\.2026 – die Verbindung zur Quelle ist getrennt, der Inhalt wird nicht aktualisiert \(zuständig: Verwaltende der Bibliothek\)/,
    )
    const row = within(drawer)
      .getAllByTestId('evidence-doc')
      .find((el) => el.getAttribute('data-file') === 'erster.md')
    expect(row).toContainElement(notices[0])
  })

  it('marks a private source as such, an uncited one too, and no other source', async () => {
    const user = userEvent.setup()
    const withPrivate: ChatMessage = {
      ...message(),
      sources: [
        source('erster.md', true, 1),
        source('zweiter.md', true, 0.5),
        source('dritter.md', true, 1 / 3),
        source('ungenutzt.md', false, 0.25, true, { privateSource: true }),
      ],
    }
    renderWithProviders(<MessageBubble message={withPrivate} />)
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })
    await user.click(within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' }))

    const marks = within(drawer).getAllByTestId('source-private')
    expect(marks).toHaveLength(1)
    expect(marks[0]).toHaveTextContent('Private Quelle')
    const row = within(drawer)
      .getAllByTestId('evidence-doc')
      .find((el) => el.getAttribute('data-file') === 'ungenutzt.md')
    expect(row).toContainElement(marks[0])
  })

  // Rank and index date are technical details for the search log, not for the Beleg.
  it('shows neither rank nor index date nor a source link in a row', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-plain',
          role: 'assistant',
          content: 'Beleg【source: a#0 | a.md】.',
          sources: [
            source('a.md', true, 1, true, {
              indexedAt: '2026-03-12T10:00:00Z',
              documentId: 'doc-a',
              sourceType: 'RSS_FEED',
              sourceEntryUrl: 'https://example.gov/feed/eintrag-7',
            }),
            source('b.md', false, 0.5, true, { indexedAt: '2026-03-12T10:00:00Z' }),
          ],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })
    await user.click(within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' }))

    expect(within(drawer).getAllByTestId('evidence-doc')).toHaveLength(2)
    expect(within(drawer).queryByText(/Rang/)).not.toBeInTheDocument()
    expect(within(drawer).queryByText(/indiziert/)).not.toBeInTheDocument()
    expect(within(drawer).queryByText(/12\.03\.2026/)).not.toBeInTheDocument()
    expect(within(drawer).queryByRole('link')).not.toBeInTheDocument()
    expect(within(drawer).queryByText('Quelle')).not.toBeInTheDocument()
  })

  it('filters by the search field', async () => {
    const { user, drawer } = await openDrawer()

    await user.type(within(drawer).getByPlaceholderText('In Belegen suchen …'), 'zweiter')

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['zweiter.md'])
  })

  it('reopens unfiltered and folded, so a clicked footnote never lands on a hidden Beleg', async () => {
    const { user, drawer } = await openDrawer()
    await user.type(within(drawer).getByPlaceholderText('In Belegen suchen …'), 'zweiter')
    await user.click(within(drawer).getByRole('button', { name: 'Nicht zitierte anzeigen' }))
    await user.keyboard('{Escape}')
    await waitForDialogClosed()

    await user.click(screen.getByRole('button', { name: 'Fundstelle 3: dritter.md' }))

    const reopened = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })
    expect(within(reopened).getByPlaceholderText('In Belegen suchen …')).toHaveValue('')
    expect(
      within(reopened).getByRole('button', { name: 'Nicht zitierte anzeigen' }),
    ).toHaveAttribute('aria-expanded', 'false')
    expect(within(reopened).getAllByTestId('evidence-doc')).toHaveLength(3)
    expect(
      within(reopened)
        .getAllByTestId('evidence-doc')
        .find((el) => el.getAttribute('data-focused') === 'true'),
    ).toHaveAttribute('data-file', 'dritter.md')
  })

  it('flags a source with an invalid citation as "Beleg nicht bestätigt" (#386)', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-invalid',
          role: 'assistant',
          content: 'Beleg【source: a#0 | schwach.md】.',
          sources: [source('schwach.md', true, 0.41, false)],
          timestamp: new Date('2026-08-21T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
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

  // A Beleg names title and Fundort only, never the metadata line.
  it('shows no metadata line, even for a source that carries metadata', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-core',
          role: 'assistant',
          content: 'Beleg【source: a#0 | 2026-03-12_da.pdf】.',
          sources: [
            source('2026-03-12_da.pdf', true, 1, true, {
              metadata: [
                {
                  fieldKey: 'document_type',
                  label: 'Dokumentart',
                  value: 'DIENSTANWEISUNG',
                  displayValue: 'Dienstanweisung',
                  origin: 'DETERMINISTIC',
                  detailOnly: false,
                },
              ],
            }),
          ],
          timestamp: new Date('2026-08-21T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const doc = within(drawer).getByTestId('evidence-doc')
    expect(within(doc).getByText('2026-03-12_da.pdf')).toBeVisible()
    expect(within(doc).queryByText('Dienstanweisung')).not.toBeInTheDocument()
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const { user } = await openDrawer()

    await user.keyboard('{Escape}')

    // The drawer leaves with a transition - wait for the unmount instead of asserting mid-exit.
    await waitForDialogClosed()
    expect(screen.getByRole('button', { name: 'Belege anzeigen' })).toHaveFocus()
  })

  describe('"Dokument öffnen" (#739)', () => {
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
      await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
      const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })
      return { user, drawer }
    }

    it('fetches and opens a local original (UPLOAD/FILESYSTEM) via the download endpoint', async () => {
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Dokument öffnen' }))

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'doc.pdf')
    })

    it('opens an HTTP_DIRECTORY document through the content endpoint too, without a source link', async () => {
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://example.gov/verzeichnis/doc.pdf',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Dokument öffnen' }))

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'doc.pdf')
      expect(within(drawer).queryByRole('link')).not.toBeInTheDocument()
    })

    it('hides the action for a synthetic entry with no documentId at all', async () => {
      const { drawer } = await openDrawerWith(source('doc.pdf', true, 0.9))

      expect(
        within(drawer).queryByRole('button', { name: 'Dokument öffnen' }),
      ).not.toBeInTheDocument()
    })

    it('shows a German error message when opening the original fails (e.g. 404)', async () => {
      mockOpenDocumentContent.mockRejectedValueOnce(
        new Error('Das Originaldokument wurde nicht gefunden.'),
      )
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Dokument öffnen' }))

      // Guidelines 5.9: the failure surfaces as a global popup notification (NotificationHost,
      // mounted by renderWithProviders), not as an inline alert inside the Belegfenster.
      expect(
        await screen.findByText('Das Originaldokument wurde nicht gefunden.'),
      ).toBeInTheDocument()
    })

    // Markdown/plain text render in a client-side dialog instead of a silent download.
    it('opens a Markdown text preview dialog instead of a silent download (#780)', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({
        kind: 'text-preview',
        fileName: '001_personalausweis.md',
        contentType: 'text/markdown',
        content: '# Personalausweis\n\nAusgestellt am 1. März.',
      })
      const { user, drawer } = await openDrawerWith(
        source('001_personalausweis.md', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Dokument öffnen' }))

      // The preview dialog portals to document.body, outside the Belegfenster drawer, so it is
      // queried at the document level rather than scoped to `drawer`.
      expect(await screen.findByText('Personalausweis')).toBeInTheDocument()
      // Heading elements are normalised per rendered content (rank compression from h2);
      // the h5 LOOK of "#" survives as the Typography variant.
      const previewHeading = screen.getByText('Personalausweis').closest('h2')
      expect(previewHeading).toBeInTheDocument()
      expect(previewHeading).toHaveClass('MuiTypography-h5')
      expect(screen.getByText(/Ausgestellt am 1\. März\./)).toBeInTheDocument()
    })

    // Every format without a preview (DOCX among them) must give visible
    // download feedback so the click never appears to do nothing.
    it('shows a snackbar with the file name when a DOCX download starts (#780)', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
      const { user, drawer } = await openDrawerWith(
        source('bescheid.docx', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Dokument öffnen' }))

      expect(await screen.findByText('bescheid.docx wird heruntergeladen')).toBeInTheDocument()
    })
  })
})
