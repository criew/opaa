import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../../test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { mockSearchStatus } from '../../mocks/searchAdminFixtures'
import DiagnosisForm from './DiagnosisForm'
import LibraryStatusTable from './LibraryStatusTable'

const { mockGetDocumentTypeVocabulary } = vi.hoisted(() => ({
  mockGetDocumentTypeVocabulary: vi.fn(async () => ({ items: [] })),
}))
vi.mock('../../services/libraryMetadataApi', () => ({
  getDocumentTypeVocabulary: mockGetDocumentTypeVocabulary,
}))

function renderStatusTable() {
  renderWithProviders(
    <LibraryStatusTable
      libraries={mockSearchStatus.libraries}
      backfillRuns={{}}
      contextPrefixRuns={{}}
      onStartBackfill={vi.fn()}
      onPauseBackfill={vi.fn()}
      onStartContextPrefixRerun={vi.fn()}
      onPauseContextPrefixRerun={vi.fn()}
    />,
  )
}

function renderDiagnosisForm() {
  renderWithProviders(
    <DiagnosisForm
      profiles={[]}
      personContextAvailable={false}
      personContextHint=""
      running={false}
      onRunDiagnosis={vi.fn(async () => {})}
    />,
  )
}

/** ADR-0044: without metadata the search administration shows none of its parts. */
describe('search administration without metadata', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ features: { metadata: false } })
  })

  afterEach(() => {
    useAuthStore.setState({ features: {} })
  })

  it('leaves the Kernfelder, Kontextpräfix and Schemaänderungen columns out', () => {
    renderStatusTable()

    expect(screen.getByRole('columnheader', { name: 'Volltextindex' })).toBeInTheDocument()
    for (const name of ['Kernfelder', 'Kontextpräfix', 'Schemaänderungen']) {
      expect(screen.queryByRole('columnheader', { name })).not.toBeInTheDocument()
    }
  })

  it('offers the diagnosis without a metadata filter and reads no vocabulary', () => {
    renderDiagnosisForm()

    expect(screen.getByLabelText(/Testfrage/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Metadatenfilter/)).not.toBeInTheDocument()
    expect(mockGetDocumentTypeVocabulary).not.toHaveBeenCalled()
  })

  it('shows both again once metadata is on', () => {
    useAuthStore.setState({ features: { metadata: true } })
    renderStatusTable()
    renderDiagnosisForm()

    expect(screen.getByRole('columnheader', { name: 'Kernfelder' })).toBeInTheDocument()
    expect(screen.getByLabelText('Metadatenfilter: Datum von')).toBeInTheDocument()
    expect(mockGetDocumentTypeVocabulary).toHaveBeenCalled()
  })
})
