import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Breadcrumbs from '@mui/material/Breadcrumbs'
import Typography from '@mui/material/Typography'
import { renderWithProviders } from '../../../test/test-utils'
import type { LibraryDocumentResponse, LibraryFolderListItem } from '@/shared/api/types'
import LibraryDocumentList from './LibraryDocumentList'
import type { LibraryDocumentListProps } from './LibraryDocumentList'

const document: LibraryDocumentResponse = {
  id: 'doc-1',
  fileName: 'vermerk.pdf',
  contentType: 'application/pdf',
  fileSize: 2048,
  status: 'INDEXED',
  sourceType: 'UPLOAD',
  chunkCount: 2,
  indexedAt: '2026-03-01T10:00:00Z',
  uploadedByUserId: null,
}

const folder: LibraryFolderListItem = { id: 'folder-1', name: 'Protokolle', documentCount: 4 }

function renderList(overrides: Partial<LibraryDocumentListProps> = {}) {
  const props: LibraryDocumentListProps = {
    documents: [document],
    folders: [folder],
    pageState: {
      page: 0,
      size: 20,
      q: '',
      totalElements: 1,
      folderId: null,
      missingMetadataField: null,
      status: null,
    },
    isLoading: false,
    emptyMessage: 'Es sind noch keine Dokumente vorhanden.',
    searchInput: '',
    onSearchChange: vi.fn(),
    onPageChange: vi.fn(),
    onPageSizeChange: vi.fn(),
    selectable: true,
    selectedIds: [],
    onToggleSelected: vi.fn(),
    onToggleAllVisible: vi.fn(),
    onClearSelection: vi.fn(),
    onOpenFolder: vi.fn(),
    metadataOpenById: {},
    onToggleMetadata: vi.fn(),
    renderMetadataPanel: () => null,
    onOpenOriginal: vi.fn(),
    isGroupExpanded: () => false,
    onToggleAttachmentGroup: vi.fn(),
    ...overrides,
  }
  return renderWithProviders(<LibraryDocumentList {...props} />, { withRouter: true })
}

describe('LibraryDocumentList (#1943)', () => {
  it('puts the breadcrumb directly above the search field', () => {
    const { container } = renderList({
      breadcrumb: (
        <Breadcrumbs aria-label="Ordnerpfad">
          <Typography>Protokolle</Typography>
        </Breadcrumbs>
      ),
    })

    const breadcrumb = screen.getByLabelText('Ordnerpfad')
    const search = screen.getByRole('textbox', { name: 'Dokumente durchsuchen' })
    // Node.DOCUMENT_POSITION_FOLLOWING: the search field comes after the breadcrumb in the
    // document, which is the whole point of the move - the path describes the list below it.
    expect(breadcrumb.compareDocumentPosition(search) & 4).toBeTruthy()
    expect(container).toBeTruthy()
  })

  it('gives a folder row no checkbox, so only documents are selectable', () => {
    renderList()

    expect(screen.getByText('Protokolle')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /Protokolle/ })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Dokument vermerk.pdf auswählen' })).toBeEnabled()
  })

  it('offers "Alle auf dieser Seite auswählen" on a root level that holds only folders', () => {
    renderList({ documents: [], folders: [folder] })

    const toolbar = screen.getByRole('toolbar', { name: 'Sammelaktionen' })
    const selectAll = within(toolbar).getByRole('checkbox', {
      name: 'Alle auf dieser Seite auswählen',
    })
    // Present, but with nothing to select - the affordance stays where it always is instead of
    // disappearing and reappearing as one walks into a folder.
    expect(selectAll).toBeDisabled()
  })

  it('carries no selection at all for a reader', () => {
    renderList({ selectable: false })

    expect(screen.queryByRole('toolbar', { name: 'Sammelaktionen' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('checkbox', { name: 'Dokument vermerk.pdf auswählen' }),
    ).not.toBeInTheDocument()
  })

  it('renders the same list for a connector document, with its space and hierarchy', () => {
    renderList({
      documents: [
        {
          ...document,
          sourceType: 'CONFLUENCE',
          sourceContainerKey: 'SOZ',
          sourceHierarchyPath: 'Handbuch / Kapitel 3',
        },
      ],
      folders: [],
      confluenceSpaces: [{ key: 'SOZ', name: 'Soziales' }],
    })

    // the origin stands in the name's tooltip, under the file name
    expect(screen.getByText('vermerk.pdf')).toHaveAttribute(
      'title',
      'vermerk.pdf\nSpace: Soziales · Handbuch / Kapitel 3',
    )
  })

  // regression guard for #2912: a source address from third-party metadata never becomes a link
  // target unless it has an http(s) scheme; the list shows any address as plain tooltip text.
  describe('source addresses', () => {
    it.each([
      ['sourceEntryUrl', 'data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=='],
      ['sourceEntryUrl', 'javascript:alert(1)'],
      ['sourceUrl', 'data:text/html,<script>alert(1)</script>'],
      ['sourceUrl', 'JaVaScRiPt:alert(1)'],
    ] as const)('shows a %s with the address %j as text, not as a link', (field, url) => {
      const { container } = renderList({
        documents: [{ ...document, sourceType: 'RSS_FEED', [field]: url }],
      })

      expect(screen.getByText('vermerk.pdf')).toHaveAttribute('title', `vermerk.pdf\n${url}`)
      expect(screen.queryAllByRole('link').filter((a) => a.textContent === url)).toEqual([])
      const hrefs = Array.from(container.querySelectorAll('[href]')).map((el) =>
        (el.getAttribute('href') ?? '').trim().toLowerCase(),
      )
      expect(
        hrefs.filter((href) => href.startsWith('javascript:') || href.startsWith('data:')),
      ).toEqual([])
    })

    it.each([
      ['sourceEntryUrl', 'https://example.gov/feed/eintrag-7'],
      ['sourceUrl', 'https://example.gov/verzeichnis/doc.pdf'],
    ] as const)(
      'names a %s with an https address in the tooltip and opens it via „Original öffnen“',
      async (field, url) => {
        const onOpenOriginal = vi.fn()
        const doc = { ...document, sourceType: 'RSS_FEED' as const, [field]: url }
        renderList({ documents: [doc], onOpenOriginal })

        expect(screen.getByText('vermerk.pdf')).toHaveAttribute('title', `vermerk.pdf\n${url}`)
        await userEvent.click(
          screen.getByRole('button', { name: 'Original von vermerk.pdf öffnen' }),
        )
        expect(onOpenOriginal).toHaveBeenCalledWith(doc)
      },
    )
  })
  it('lists an attachment on its own under a status filter and names its parent', () => {
    const failedAttachment: LibraryDocumentResponse = {
      ...document,
      id: 'doc-2',
      fileName: 'anlage.pdf',
      status: 'FAILED',
      parentDocumentId: 'mail-1',
      parentFileName: 'posteingang.eml',
    }
    renderList({
      documents: [{ ...document, status: 'FAILED' }, failedAttachment],
      folders: [],
      pageState: {
        page: 0,
        size: 20,
        q: '',
        totalElements: 2,
        folderId: null,
        missingMetadataField: null,
        status: 'FAILED',
      },
    })

    // a row of its own, not folded under the document before it
    expect(screen.queryByRole('button', { name: /Anhänge von vermerk\.pdf/ })).toBeNull()
    expect(screen.getByText('anlage.pdf')).toBeInTheDocument()
    expect(screen.getByText(/Anhang von posteingang\.eml/)).toBeInTheDocument()
    // a filtered list counts its rows in the foot
    expect(screen.getByText('2 Dokumente')).toBeInTheDocument()
  })
})
