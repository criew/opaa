import { screen } from '@testing-library/react'
import { describe, expect, it, beforeEach } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import IndexingSnackbar from './IndexingSnackbar'
import { useIndexingStore } from '../../stores/indexingStore'

describe('IndexingSnackbar', () => {
  beforeEach(() => {
    useIndexingStore.setState({
      snackbar: { open: false, message: '', severity: 'success' },
    })
  })

  it('does not show snackbar when closed', () => {
    renderWithProviders(<IndexingSnackbar />)

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('shows success snackbar', () => {
    useIndexingStore.setState({
      snackbar: { open: true, message: 'Indexing completed: 42 documents', severity: 'success' },
    })
    renderWithProviders(<IndexingSnackbar />)

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.getByText('Indexing completed: 42 documents')).toBeInTheDocument()
  })

  it('shows error snackbar', () => {
    useIndexingStore.setState({
      snackbar: { open: true, message: 'Indizierung fehlgeschlagen', severity: 'error' },
    })
    renderWithProviders(<IndexingSnackbar />)

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.getByText('Indizierung fehlgeschlagen')).toBeInTheDocument()
  })
})
