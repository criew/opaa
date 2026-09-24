import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Breadcrumbs from '@mui/material/Breadcrumbs'
import Typography from '@mui/material/Typography'
import { renderWithProviders } from '../../test/test-utils'
import type { LibraryDocumentResponse, LibraryFolderListItem } from '../../types/api'
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

  it('shows a folder as not selectable rather than hiding the column', () => {
    renderList()

    const folderCheckbox = screen.getByRole('checkbox', {
      name: 'Ordner Protokolle ist nicht auswählbar',
    })
    expect(folderCheckbox).toBeDisabled()
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

    expect(screen.getByText('Space: Soziales (SOZ) · Handbuch / Kapitel 3')).toBeInTheDocument()
  })
})
