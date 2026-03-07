import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import WorkspaceManagementPage from './WorkspaceManagementPage'
import { useWorkspaceStore } from '../stores/workspaceStore'

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useParams: () => ({ workspaceId: 'ws-personal' }),
    useNavigate: () => vi.fn(),
  }
})

describe('WorkspaceManagementPage', () => {
  beforeEach(() => {
    useWorkspaceStore.setState({
      workspaces: [],
      selectedWorkspaceId: 'ws-personal',
      selectedWorkspace: {
        id: 'ws-personal',
        name: 'My Documents',
        description: 'Private docs',
        type: 'PERSONAL',
        ownerId: 'u1',
        memberCount: 1,
        userRole: 'OWNER',
        roleCounts: { VIEWER: 0, EDITOR: 0, ADMIN: 0, OWNER: 1 },
        members: [{ userId: 'u1', role: 'OWNER', createdAt: '2026-03-01T10:00:00Z' }],
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
      selectedWorkspaceDocuments: [],
      chatFilterWorkspaceId: null,
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
  })

  it('shows personal workspace message and disables member management', () => {
    renderWithProviders(<WorkspaceManagementPage />, { withRouter: true })
    expect(screen.getByText(/personal workspace/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /add member/i })).not.toBeInTheDocument()
  })
})
