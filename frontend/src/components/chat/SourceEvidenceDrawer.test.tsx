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
    ...extra,
  }
}

/**
 * An answer citing three documents, plus one checked-but-uncited source. #1102: `sources` arrives
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
      within(drawer).getByText('3 Stellen in 3 Dokumenten · nach Relevanzrang sortiert'),
    ).toBeInTheDocument()
    expect(within(drawer).getByText(/Stand der Antwort: 20\.08\.2026, 14:12/)).toBeInTheDocument()
  })

  it('lists documents in the order the pipeline selected them, uncited ones at the end', async () => {
    const { drawer } = await openDrawer()

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['erster.md', 'zweiter.md', 'dritter.md', 'ungenutzt.md'])
  })

  // #1102: relevanceScore is the reciprocal of the fused rank, so it is only ever a label - a
  // persisted message from before #1102 still carries the old path-dependent raw score, and the
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
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['lexikalisch.md', 'vektor.md'])
  })

  it('labels a source with its rank in the answer, not a percentage weight (#1102)', async () => {
    const { drawer } = await openDrawer()

    const rows = within(drawer).getAllByTestId('evidence-doc')
    expect(within(rows[0]).getByText(/Rang 1/)).toBeInTheDocument()
    expect(within(rows[1]).getByText(/Rang 2/)).toBeInTheDocument()
    expect(within(rows[2]).getByText(/Rang 3/)).toBeInTheDocument()
    expect(within(drawer).queryByText(/Gewicht/)).not.toBeInTheDocument()
  })

  // #1102: the rank is the row's position in the drawer's own, unfiltered list - a message
  // persisted before #1102 carries raw path-dependent scores in its snapshot, and labelling from
  // 1 / relevanceScore would call the pipeline's first source "Rang 11".
  it('labels a legacy message by row position, not by 1 / relevanceScore', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-legacy-rank',
          role: 'assistant',
          content: 'Beleg【source: a#0 | lexikalisch.md】 und【source: b#0 | vektor.md】.',
          sources: [source('lexikalisch.md', true, 0.09), source('vektor.md', true, 0.8)],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const rows = within(drawer).getAllByTestId('evidence-doc')
    expect(within(rows[0]).getByText(/Rang 1/)).toBeInTheDocument()
    expect(within(rows[1]).getByText(/Rang 2/)).toBeInTheDocument()
  })

  // #1102: "Rang n" is the source's position in the backend's `sources` array - the same position
  // `relevanceScore` is the reciprocal of - not the row's position in this list. The list groups
  // the cited rows before the uncited ones, so a row position would renumber as soon as an uncited
  // source sits between two cited ones (the normal case: the model does not cite every hit).
  it('numbers by the position in `sources` when an uncited source sits between two cited ones', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-interleaved',
          role: 'assistant',
          content: 'Erstens【source: a#0 | a.md】, drittens【source: c#0 | c.md】.',
          sources: [
            source('a.md', true, 1),
            source('b.md', false, 0.5),
            source('c.md', true, 1 / 3),
          ],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const rows = within(drawer).getAllByTestId('evidence-doc')
    expect(rows.map((el) => el.getAttribute('data-file'))).toEqual(['a.md', 'c.md', 'b.md'])
    expect(within(rows[0]).getByText(/Rang 1$/)).toBeInTheDocument()
    expect(within(rows[1]).getByText(/Rang 3$/)).toBeInTheDocument()
    expect(within(rows[2]).getByText(/Rang 2$/)).toBeInTheDocument()
  })

  it('keeps a row rank when the list is filtered', async () => {
    const { user, drawer } = await openDrawer()

    await user.type(within(drawer).getByPlaceholderText('In Belegen suchen …'), 'dritter')

    const rows = within(drawer).getAllByTestId('evidence-doc')
    expect(rows).toHaveLength(1)
    expect(within(rows[0]).getByText(/Rang 3/)).toBeInTheDocument()
  })

  // #386: a synthetic entry backs no retrieved passage (relevanceScore 0), so it holds no rank -
  // and consumes none either, leaving the numbering of the real rows gap-free.
  it('gives a synthetic entry no rank and lets it consume none', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-synthetic',
          role: 'assistant',
          content:
            'Echt【source: a#0 | echt.md】, erfunden【source: x#0 | erfunden.md】, ' +
            'ungenutzt bleibt übrig.',
          sources: [
            source('echt.md', true, 1),
            source('erfunden.md', true, 0, false),
            source('ungenutzt.md', false, 0.5),
          ],
          timestamp: new Date('2026-08-22T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const rows = within(drawer).getAllByTestId('evidence-doc')
    expect(rows.map((el) => el.getAttribute('data-file'))).toEqual([
      'echt.md',
      'erfunden.md',
      'ungenutzt.md',
    ])
    expect(within(rows[0]).getByText(/Rang 1/)).toBeInTheDocument()
    expect(within(rows[1]).queryByText(/Rang/)).not.toBeInTheDocument()
    expect(within(rows[2]).getByText(/Rang 2/)).toBeInTheDocument()
  })

  it('filters by the search field', async () => {
    const { user, drawer } = await openDrawer()

    await user.type(within(drawer).getByPlaceholderText('In Belegen suchen …'), 'zweiter')

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['zweiter.md'])
  })

  it('hides checked-but-uncited sources behind the "Nur zitierte" filter', async () => {
    const { user, drawer } = await openDrawer()

    await user.click(within(drawer).getByRole('button', { name: 'Nur zitierte' }))

    const names = within(drawer)
      .getAllByTestId('evidence-doc')
      .map((el) => el.getAttribute('data-file'))
    expect(names).toEqual(['erster.md', 'zweiter.md', 'dritter.md'])
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

  // #1164: a source whose retrieved chunk carried mail_* metadata shows a summary line; a
  // non-mail source (the default `message()` fixture) shows none.
  it('shows the mail Kopfdaten summary for a source with mail metadata', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-mail',
          role: 'assistant',
          content: 'Beleg【source: a#0 | anfrage.eml】.',
          sources: [
            source('anfrage.eml', true, 1, true, {
              mailFrom: 'mueller@stadt.de',
              mailDate: '2026-03-14T09:15:00Z',
              mailSubject: 'Bebauungsplan Nord',
            }),
          ],
          timestamp: new Date('2026-08-21T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const doc = within(drawer).getByTestId('evidence-doc')
    expect(within(doc).getByTestId('source-mail-summary')).toHaveTextContent(
      'Mail von mueller@stadt.de, 14.03.2026 — Bebauungsplan Nord',
    )
  })

  // #1066: the Belegfenster row carries the same core-field line as the Fundstellen block, with
  // the title as the label and the file name as secondary text.
  it('shows the core metadata of a source and labels the row by its title', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <MessageBubble
        message={{
          id: 'ev-core',
          role: 'assistant',
          content: 'Beleg【source: a#0 | 2026-03-12_da.pdf】.',
          sources: [
            source('2026-03-12_da.pdf', true, 1, true, {
              coreMetadata: {
                title: 'Dienstanweisung IT-Nutzung',
                titleOrigin: 'DETERMINISTIC',
                documentType: 'DIENSTANWEISUNG',
                documentTypeLabel: 'Dienstanweisung',
                documentTypeOrigin: 'DETERMINISTIC',
                documentDate: '2026-03-01',
                documentDatePrecision: 'MONTH',
                documentDateOrigin: 'DETERMINISTIC',
              },
            }),
          ],
          timestamp: new Date('2026-08-21T09:00:00'),
        }}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Alle als Liste im Belegfenster öffnen' }))
    const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })

    const doc = within(drawer).getByTestId('evidence-doc')
    expect(within(doc).getByText('Dienstanweisung IT-Nutzung')).toBeVisible()
    expect(within(doc).getByTestId('source-file-name')).toHaveTextContent('2026-03-12_da.pdf')
    expect(within(doc).getByTestId('source-core-metadata')).toHaveTextContent(
      'Dienstanweisung · Stand März 2026',
    )
  })

  it('shows no core metadata line for a source without core fields', async () => {
    const { drawer } = await openDrawer()

    expect(within(drawer).queryByTestId('source-core-metadata')).not.toBeInTheDocument()
  })

  it('shows no mail summary for a source without mail metadata', async () => {
    const { drawer } = await openDrawer()

    expect(within(drawer).queryByTestId('source-mail-summary')).not.toBeInTheDocument()
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
        source('doc.pdf', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'doc.pdf')
    })

    it('opens an HTTP_DIRECTORY document through the content endpoint too, keeping sourceUrl as a secondary "Quelle" link (#747)', async () => {
      const { user, drawer } = await openDrawerWith(
        source('doc.pdf', true, 0.9, true, {
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
        source('doc.pdf', true, 0.9, true, {
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
        source('001_personalausweis.md', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      // The preview dialog portals to document.body, outside the Belegfenster drawer, so it is
      // queried at the document level rather than scoped to `drawer`.
      expect(await screen.findByText('Personalausweis')).toBeInTheDocument()
      // #1016: heading elements are normalised per rendered content (rank compression from h2);
      // the h5 LOOK of "#" survives as the Typography variant.
      const previewHeading = screen.getByText('Personalausweis').closest('h2')
      expect(previewHeading).toBeInTheDocument()
      expect(previewHeading).toHaveClass('MuiTypography-h5')
      expect(screen.getByText(/Ausgestellt am 1\. März\./)).toBeInTheDocument()
    })

    // #780 acceptance criteria: every format without a preview (DOCX among them) must give visible
    // download feedback so the click never appears to do nothing.
    it('shows a snackbar with the file name when a DOCX download starts (#780)', async () => {
      mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
      const { user, drawer } = await openDrawerWith(
        source('bescheid.docx', true, 0.9, true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      )

      await user.click(within(drawer).getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(await screen.findByText('bescheid.docx wird heruntergeladen')).toBeInTheDocument()
    })
  })
})
