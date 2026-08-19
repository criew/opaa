import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import ChatList from './ChatList'
import { useChatListStore } from '../../stores/chatListStore'

const mockNavigate = vi.fn()
let currentPathname = '/spaces/space-personal'

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ pathname: currentPathname }),
  }
})

describe('ChatList', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    currentPathname = '/spaces/space-personal'
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it('loads and displays the space chats with title and timestamp', async () => {
    renderWithProviders(<ChatList spaceId="space-personal" />)

    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })

  it('shows a placeholder when the space has no chats', async () => {
    renderWithProviders(<ChatList spaceId="space-phoenix" />)

    expect(await screen.findByText('Noch keine Chats in diesem Space.')).toBeInTheDocument()
  })

  it('creates a new chat and navigates to it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByRole('button', { name: 'Neuer Chat' }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        expect.stringMatching(/^\/spaces\/space-personal\/chats\/.+$/),
      )
    })
  })

  it('renames a chat via the edit action', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ umbenennen'))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel{Enter}')

    expect(await screen.findByText('Neuer Titel')).toBeInTheDocument()
  })

  it('deletes a chat after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))

    await waitFor(() => {
      expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    })
  })

  it('does not delete a chat when the confirmation is cancelled', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))

    expect(screen.getByText('Architektur des Projekts')).toBeInTheDocument()
  })
})
