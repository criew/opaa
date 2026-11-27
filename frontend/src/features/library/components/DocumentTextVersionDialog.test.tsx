import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { DocumentTextPassage, DocumentTextResponse } from '@/shared/api/types'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import DocumentTextVersionDialog from './DocumentTextVersionDialog'
import { downloadDocumentContent } from '../utils/documentContent'

vi.mock('../utils/documentContent', () => ({ downloadDocumentContent: vi.fn() }))

const TEXT = '/api/v1/documents/:documentId/text'

function passage(chunkIndex: number, quoteStart = `Passage ${chunkIndex}`): DocumentTextPassage {
  return {
    chunkIndex,
    text: `Passage ${chunkIndex} der Folien.`,
    location: `Folie ${chunkIndex + 1}`,
    anchor: { slide: chunkIndex + 1, quoteStart },
  }
}

/** A deck of `count` passages, one per slide, windowed like the endpoint. */
function serveDeck(count: number, requests: URLSearchParams[] = []) {
  server.use(
    http.get(TEXT, ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      const from = Number(params.get('from') ?? 0)
      const to = Math.min(Number(params.get('to') ?? from + 39), from + 39)
      const response: DocumentTextResponse = {
        documentId: 'doc-1',
        fileName: 'vortrag.pptx',
        firstChunkIndex: 0,
        lastChunkIndex: count - 1,
        passages: Array.from({ length: count }, (_, i) => passage(i)).filter(
          (p) => p.chunkIndex >= from && p.chunkIndex <= to,
        ),
        sections:
          params.get('sections') === 'true'
            ? Array.from({ length: count }, (_, i) => ({
                chunkIndex: i,
                label: `Folie ${i + 1}`,
                level: 1,
              }))
            : [],
        sectionsTruncated: false,
      }
      return HttpResponse.json(response)
    }),
  )
}

describe('DocumentTextVersionDialog', () => {
  it('stays closed without a preview', () => {
    renderWithProviders(<DocumentTextVersionDialog preview={null} onClose={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('opens at the cited passage, marks it and loads a window around it', async () => {
    const requests: URLSearchParams[] = []
    serveDeck(100, requests)

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{
          documentId: 'doc-1',
          fileName: 'vortrag.pptx',
          chunkIndex: 50,
          anchor: { slide: 51, quoteStart: 'Passage 50' },
        }}
        onClose={vi.fn()}
      />,
    )

    const cited = await screen.findByLabelText('Fundstelle')
    expect(cited).toHaveTextContent('Passage 50 der Folien.')
    expect(cited).toHaveAttribute('data-chunk-index', '50')
    await waitFor(() => expect(cited).toHaveFocus())
    expect(screen.getByRole('status')).toHaveTextContent('Fundstelle markiert.')
    expect(screen.getByText(/Layout des Originals wird nicht wiedergegeben/)).toBeVisible()
    expect(requests[0].get('from')).toBe('45')
    expect(requests[0].get('to')).toBe('84')
    expect(requests[0].get('sections')).toBe('true')
  })

  it('says so when the cited passage was indexed anew since the citation', async () => {
    serveDeck(10)

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{
          documentId: 'doc-1',
          fileName: 'vortrag.pptx',
          chunkIndex: 3,
          anchor: { quoteStart: 'Ein anderer Wortlaut' },
        }}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText(/neu indexiert/)).toHaveAttribute('role', 'status')
  })

  it('opens at the beginning when the cited passage is beyond the current index', async () => {
    const requests: URLSearchParams[] = []
    serveDeck(5, requests)

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'vortrag.pptx', chunkIndex: 80 }}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('Passage 0 der Folien.')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/nicht mehr enthalten/)
    expect(requests).toHaveLength(2)
  })

  it('loads earlier and later text on request', async () => {
    serveDeck(100)
    const user = userEvent.setup()

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'vortrag.pptx', chunkIndex: 50 }}
        onClose={vi.fn()}
      />,
    )
    await screen.findByLabelText('Fundstelle')
    expect(screen.queryByText('Passage 44 der Folien.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Vorherigen Text laden' }))
    expect(await screen.findByText('Passage 44 der Folien.')).toBeInTheDocument()
    expect(screen.getByText('Passage 5 der Folien.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Weiteren Text laden' }))
    expect(await screen.findByText('Passage 85 der Folien.')).toBeInTheDocument()
  })

  it('jumps to a section outside the loaded window through the outline', async () => {
    serveDeck(100)
    const user = userEvent.setup()

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'vortrag.pptx', chunkIndex: 0 }}
        onClose={vi.fn()}
      />,
    )
    // a narrow window starts with the outline folded away
    await user.click(await screen.findByRole('button', { name: 'Gliederung' }))
    const outline = screen.getByRole('navigation', { name: 'Gliederung' })
    expect(within(outline).getByRole('button', { name: 'Folie 1' })).toHaveAttribute(
      'aria-current',
      'location',
    )

    await user.click(within(outline).getByRole('button', { name: 'Folie 91' }))

    expect(await screen.findByText('Passage 90 der Folien.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('Passage 90 der Folien.')).toHaveFocus())
    expect(screen.queryByText('Passage 0 der Folien.')).not.toBeInTheDocument()
    // the cited passage is merely out of view, not gone
    expect(screen.getByRole('status')).not.toHaveTextContent(/nicht mehr enthalten/)
  })

  // regression guard for #3880: the outline marks the section chosen, then the one scrolled to
  it('marks the section chosen in the outline, then the section the reader scrolls to', async () => {
    serveDeck(10)
    const user = userEvent.setup()

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'vortrag.pptx', chunkIndex: 0 }}
        onClose={vi.fn()}
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Gliederung' }))
    const outline = screen.getByRole('navigation', { name: 'Gliederung' })
    const entry = (label: string) => within(outline).getByRole('button', { name: label })
    expect(entry('Folie 1')).toHaveAttribute('aria-current', 'location')

    await user.click(entry('Folie 4'))
    expect(entry('Folie 4')).toHaveAttribute('aria-current', 'location')
    expect(entry('Folie 1')).not.toHaveAttribute('aria-current')
    // the scroll event of the view's own jump keeps the choice
    fireEvent.scroll(screen.getByTestId('text-version-content'))
    expect(entry('Folie 4')).toHaveAttribute('aria-current', 'location')

    // each passage, heading included, 100 px tall; the view 400 px, its reading line at 100 px
    const content = screen.getByTestId('text-version-content')
    Object.defineProperty(content, 'clientHeight', { configurable: true, value: 400 })
    Object.defineProperty(content, 'scrollHeight', { configurable: true, value: 2000 })
    for (const block of content.querySelectorAll<HTMLElement>('[data-chunk-index]')) {
      const top = Number(block.dataset.chunkIndex) * 100
      block.parentElement!.getBoundingClientRect = () =>
        ({ top: top - content.scrollTop, left: 0, width: 600, height: 100 }) as DOMRect
    }
    content.scrollTop = 650
    fireEvent.scroll(content)

    expect(entry('Folie 8')).toHaveAttribute('aria-current', 'location')
    expect(entry('Folie 4')).not.toHaveAttribute('aria-current')
  })

  it('shows nested sections as a tree that folds like the PDF outline', async () => {
    server.use(
      http.get(TEXT, () =>
        HttpResponse.json({
          documentId: 'doc-1',
          fileName: 'satzung.odt',
          firstChunkIndex: 0,
          lastChunkIndex: 2,
          passages: [0, 1, 2].map((i) => ({ chunkIndex: i, text: `Text ${i}.` })),
          sections: [
            { chunkIndex: 0, label: 'Teil 1', level: 1 },
            { chunkIndex: 1, label: '§ 1 Zweck', level: 2 },
            { chunkIndex: 2, label: 'Teil 2', level: 1 },
          ],
          sectionsTruncated: true,
        } satisfies DocumentTextResponse),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'satzung.odt' }}
        onClose={vi.fn()}
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Gliederung' }))
    const outline = screen.getByRole('navigation', { name: 'Gliederung' })
    expect(within(outline).getByRole('button', { name: '§ 1 Zweck' })).toBeVisible()
    expect(within(outline).getByText('Die Gliederung ist gekürzt.')).toBeVisible()

    await user.click(within(outline).getByRole('button', { name: 'Teil 1 zuklappen' }))
    expect(within(outline).queryByRole('button', { name: '§ 1 Zweck' })).toBeNull()
    expect(within(outline).getByRole('button', { name: 'Teil 2' })).toBeVisible()
  })

  it('says when nothing of the document is indexed', async () => {
    server.use(
      http.get(TEXT, () =>
        HttpResponse.json({
          documentId: 'doc-1',
          fileName: 'leer.docx',
          firstChunkIndex: null,
          lastChunkIndex: null,
          passages: [],
          sections: [],
          sectionsTruncated: false,
        }),
      ),
    )

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'leer.docx' }}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText(/keine Textfassung vor/)).toBeInTheDocument()
  })

  it('shows the error the endpoint answers with', async () => {
    server.use(
      http.get(TEXT, () =>
        HttpResponse.json(
          { error: 'Die Bibliothek wird gelöscht', code: 'LIBRARY_BEING_ERASED' },
          { status: 404 },
        ),
      ),
    )

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'vortrag.pptx' }}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('Die Bibliothek wird gelöscht')
  })

  it('downloads the original from the title bar', async () => {
    serveDeck(3)
    const user = userEvent.setup()

    renderWithProviders(
      <DocumentTextVersionDialog
        preview={{ documentId: 'doc-1', fileName: 'vortrag.pptx' }}
        onClose={vi.fn()}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Original herunterladen' }))

    expect(downloadDocumentContent).toHaveBeenCalledWith('doc-1', 'vortrag.pptx')
  })
})
