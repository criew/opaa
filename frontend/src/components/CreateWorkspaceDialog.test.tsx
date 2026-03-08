import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import CreateWorkspaceDialog from './CreateWorkspaceDialog'
import { useWorkspaceStore } from '../stores/workspaceStore'

vi.mock('../services/api', () => ({
  getWorkspaces: vi.fn(async () => []),
  getWorkspace: vi.fn(async () => ({})),
  getWorkspaceDocuments: vi.fn(async () => []),
  createWorkspace: vi.fn(async () => ({
    id: 'ws-new',
    name: 'Test',
    description: '',
    type: 'SHARED',
    ownerId: 'u1',
    memberCount: 1,
    userRole: 'OWNER',
    roleCounts: { VIEWER: 0, EDITOR: 0, ADMIN: 0, OWNER: 1 },
    members: [{ userId: 'u1', role: 'OWNER', createdAt: '2026-03-01T10:00:00Z' }],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })),
}))

describe('CreateWorkspaceDialog', () => {
  const onClose = vi.fn()
  const onCreated = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    useWorkspaceStore.setState({
      workspaces: [],
      selectedWorkspaceId: null,
      selectedWorkspace: null,
      selectedWorkspaceDocuments: [],
      chatFilterWorkspaceIds: [],
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
  })

  it('renders name and description fields', () => {
    renderWithProviders(
      <CreateWorkspaceDialog open={true} onClose={onClose} onCreated={onCreated} />,
    )
    expect(screen.getByLabelText(/name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/description/i)).toBeInTheDocument()
  })

  it('disables create button when name is empty', () => {
    renderWithProviders(
      <CreateWorkspaceDialog open={true} onClose={onClose} onCreated={onCreated} />,
    )
    expect(screen.getByRole('button', { name: /create/i })).toBeDisabled()
  })

  it('calls onCreated with workspace id after submit', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <CreateWorkspaceDialog open={true} onClose={onClose} onCreated={onCreated} />,
    )

    await user.type(screen.getByLabelText(/name/i), 'My New Workspace')
    await user.click(screen.getByRole('button', { name: /create/i }))

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledWith('ws-new')
    })
  })

  it('calls onClose when cancel is clicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <CreateWorkspaceDialog open={true} onClose={onClose} onCreated={onCreated} />,
    )

    await user.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onClose).toHaveBeenCalled()
  })
})
