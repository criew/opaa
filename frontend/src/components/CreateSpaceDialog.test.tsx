import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import CreateSpaceDialog from './CreateSpaceDialog'
import { useSpaceStore } from '../stores/spaceStore'
import type { createSpace } from '../services/api'

// Typed as the real createSpace so the mock wiring below can hand it straight to vi.mock without a
// spreading wrapper (which would otherwise need a named, and therefore "unused", rest parameter).
const { mockCreateSpace } = vi.hoisted(() => ({
  mockCreateSpace: vi.fn(async () => ({
    id: 'space-new',
    name: 'Test',
    description: '',
    isDefault: false,
    visibility: 'PRIVATE',
    ownerId: 'u1',
    memberCount: 1,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
    members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })) as unknown as typeof createSpace,
}))

vi.mock('../services/api', () => ({
  getSpaces: vi.fn(async () => []),
  getSpace: vi.fn(async () => ({})),
  createSpace: mockCreateSpace,
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

  // #272: PRIVATE is the visibility default for a newly created space
  // (docs/features/spaces-and-assets.md#space-sichtbarkeit).
  it('defaults to PRIVATE visibility when the user submits without changing it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)

    await user.type(screen.getByLabelText(/name/i), 'My New Space')
    await user.click(screen.getByRole('button', { name: /erstellen/i }))

    await waitFor(() => {
      expect(mockCreateSpace).toHaveBeenCalledWith('My New Space', '', 'PRIVATE')
    })
  })

  it('sends the chosen visibility when the user changes it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)

    await user.type(screen.getByLabelText(/name/i), 'My New Space')
    await user.click(screen.getByRole('combobox', { name: /sichtbarkeit/i }))
    await user.click(await screen.findByRole('option', { name: /^offen$/i }))
    await user.click(screen.getByRole('button', { name: /erstellen/i }))

    await waitFor(() => {
      expect(mockCreateSpace).toHaveBeenCalledWith('My New Space', '', 'OPEN')
    })
  })

  it('calls onClose when cancel is clicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CreateSpaceDialog open={true} onClose={onClose} onCreated={onCreated} />)

    await user.click(screen.getByRole('button', { name: /abbrechen/i }))
    expect(onClose).toHaveBeenCalled()
  })
})
