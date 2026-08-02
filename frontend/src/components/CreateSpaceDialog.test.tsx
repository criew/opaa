import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import CreateSpaceDialog from './CreateSpaceDialog'
import { useSpaceStore } from '../stores/spaceStore'

vi.mock('../services/api', () => ({
  getSpaces: vi.fn(async () => []),
  getSpace: vi.fn(async () => ({})),
  createSpace: vi.fn(async () => ({
    id: 'space-new',
    name: 'Test',
    description: '',
    kind: 'PROJECT',
    visibility: 'PRIVATE',
    ownerId: 'u1',
    memberCount: 1,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
    members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })),
}))

describe('CreateSpaceDialog', () => {
  const onClose = vi.fn()
  const onCreated = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    useSpaceStore.setState({
      spaces: [],
      selectedSpaceId: null,
      selectedSpace: null,
      chatFilterSpaceIds: [],
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
  })

  it('renders name and description fields', () => {
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)
    expect(screen.getByLabelText(/name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/beschreibung/i)).toBeInTheDocument()
  })

  it('disables create button when name is empty', () => {
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)
    expect(screen.getByRole('button', { name: /erstellen/i })).toBeDisabled()
  })

  it('calls onCreated with space id after submit', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)

    await user.type(screen.getByLabelText(/name/i), 'My New Space')
    await user.click(screen.getByRole('button', { name: /erstellen/i }))

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledWith('space-new')
    })
  })

  it('calls onClose when cancel is clicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)

    await user.click(screen.getByRole('button', { name: /abbrechen/i }))
    expect(onClose).toHaveBeenCalled()
  })
})
