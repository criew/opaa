import { screen } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import SpacePage from './SpacePage'
import { useSpaceStore } from '../stores/spaceStore'
import { useChatListStore } from '../stores/chatListStore'

const currentSpaceId = 'space-personal'
const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: currentSpaceId }),
    useNavigate: () => mockNavigate,
  }
})

describe('SpacePage', () => {
  beforeEach(() => {
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Meine Dokumente',
          description: 'Private Dokumente',
          isDefault: true,
          archived: false,
          visibility: 'PRIVATE',
          memberCount: 1,
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      selectedSpaceId: 'space-personal',
      selectedSpace: {
        id: 'space-personal',
        name: 'Meine Dokumente',
        description: 'Private Dokumente',
        isDefault: true,
        archived: false,
        visibility: 'PRIVATE',
        ownerId: 'mock-user-id',
        memberCount: 1,
        userRole: 'ADMIN',
        roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
      members: [
        {
          userId: 'mock-user-id',
          displayName: 'Admin',
          role: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingMembers: false,
    })
  })

  it('lists the space chats in the Chats section', async () => {
    renderWithProviders(<SpacePage />, { withRouter: true })

    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })
})
