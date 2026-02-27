import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, beforeEach } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AdminDrawer from './AdminDrawer'
import { useIndexingStore } from '../../stores/indexingStore'

describe('AdminDrawer', () => {
  beforeEach(() => {
    useIndexingStore.setState({
      status: 'IDLE',
      documentCount: 0,
      totalDocuments: 0,
      documentsSkipped: 0,
      message: null,
      timestamp: null,
      isPolling: false,
      drawerOpen: true,
      snackbar: { open: false, message: '', severity: 'success' },
    })
  })

  it('renders admin header and indexing button', () => {
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByText('Admin')).toBeInTheDocument()
    expect(screen.getByText('Index Documents')).toBeInTheDocument()
  })

  it('shows close button', () => {
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByLabelText('Close admin drawer')).toBeInTheDocument()
  })

  it('closes drawer on close button click', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdminDrawer />)

    await user.click(screen.getByLabelText('Close admin drawer'))
    expect(useIndexingStore.getState().drawerOpen).toBe(false)
  })

  it('disables button when indexing is running', () => {
    useIndexingStore.setState({ status: 'RUNNING' })
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByRole('button', { name: /indexing/i })).toBeDisabled()
  })

  it('shows indeterminate progress when total is unknown', () => {
    useIndexingStore.setState({
      status: 'RUNNING',
      totalDocuments: 0,
      documentCount: 0,
    })
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.getByText(/discovering documents/i)).toBeInTheDocument()
  })

  it('shows determinate progress with document counts', () => {
    useIndexingStore.setState({
      status: 'RUNNING',
      totalDocuments: 42,
      documentCount: 10,
      documentsSkipped: 0,
    })
    renderWithProviders(<AdminDrawer />)

    const progressbar = screen.getByRole('progressbar')
    expect(progressbar).toBeInTheDocument()
    expect(progressbar).toHaveAttribute('aria-valuenow', '24')
    expect(screen.getByText(/10 of 42 documents processed/i)).toBeInTheDocument()
  })

  it('shows last indexing info when completed', () => {
    useIndexingStore.setState({
      status: 'COMPLETED',
      documentCount: 42,
      totalDocuments: 42,
      documentsSkipped: 0,
      timestamp: '2025-01-15T10:30:00Z',
    })
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByText(/completed/i)).toBeInTheDocument()
    expect(screen.getByText(/documents: 42 processed/i)).toBeInTheDocument()
  })

  it('shows skipped count when documents were skipped', () => {
    useIndexingStore.setState({
      status: 'COMPLETED',
      documentCount: 32,
      totalDocuments: 42,
      documentsSkipped: 10,
      timestamp: '2025-01-15T10:30:00Z',
    })
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByText(/10 skipped/i)).toBeInTheDocument()
  })

  it('does not show skipped text when zero skipped', () => {
    useIndexingStore.setState({
      status: 'COMPLETED',
      documentCount: 42,
      totalDocuments: 42,
      documentsSkipped: 0,
      timestamp: '2025-01-15T10:30:00Z',
    })
    renderWithProviders(<AdminDrawer />)

    expect(screen.queryByText(/skipped/i)).not.toBeInTheDocument()
  })

  it('does not render content when drawer is closed', () => {
    useIndexingStore.setState({ drawerOpen: false })
    renderWithProviders(<AdminDrawer />)

    expect(screen.queryByText('Admin')).not.toBeInTheDocument()
  })
})
