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

  it('shows progress when indexing is running', () => {
    useIndexingStore.setState({
      status: 'RUNNING',
      message: 'Indexing in progress... 10 documents processed',
    })
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.getByText(/10 documents processed/)).toBeInTheDocument()
  })

  it('shows last indexing info when completed', () => {
    useIndexingStore.setState({
      status: 'COMPLETED',
      documentCount: 42,
      timestamp: '2025-01-15T10:30:00Z',
    })
    renderWithProviders(<AdminDrawer />)

    expect(screen.getByText(/completed/i)).toBeInTheDocument()
    expect(screen.getByText(/documents: 42/i)).toBeInTheDocument()
  })

  it('does not render content when drawer is closed', () => {
    useIndexingStore.setState({ drawerOpen: false })
    renderWithProviders(<AdminDrawer />)

    expect(screen.queryByText('Admin')).not.toBeInTheDocument()
  })
})
