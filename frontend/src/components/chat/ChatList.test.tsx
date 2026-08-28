import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
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

  // #548 review, nit a: "Neuer Chat" must not eagerly persist an empty chat - it only navigates to
  // the not-yet-created "new" chat state, so the implicit creation on the first sent message
  // (chatStore#sendMessage) stays the single chat-creation path.
  it('navigates to the not-yet-created chat instead of eagerly creating one', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByRole('button', { name: 'Neuer Chat' }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/new')
    // The existing chats are untouched - nothing was created.
    expect(useChatListStore.getState().chatsBySpaceId['space-personal']).toHaveLength(2)
  })

  it('renames a chat via the edit action', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
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

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
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

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))

    expect(screen.getByText('Architektur des Projekts')).toBeInTheDocument()
  })

  // #548 review, nit b: a failed delete used to reject silently (unhandled rejection) instead of
  // surfacing anything to the user.
  it('shows an error and keeps the chat when deletion fails on the server', async () => {
    server.use(
      http.delete('/api/v1/chats/:chatId', () => {
        return HttpResponse.json({ error: 'Löschen fehlgeschlagen' }, { status: 500 })
      }),
    )
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))

    expect(await screen.findByText('Löschen fehlgeschlagen')).toBeInTheDocument()
    expect(screen.getByText('Architektur des Projekts')).toBeInTheDocument()
  })

  // #548 review, nit b: same for a failed rename.
  it('shows an error and keeps the old title when renaming fails on the server', async () => {
    server.use(
      http.patch('/api/v1/chats/:chatId', () => {
        return HttpResponse.json({ error: 'Umbenennen fehlgeschlagen' }, { status: 500 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ umbenennen'))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel{Enter}')

    expect(await screen.findByText('Umbenennen fehlgeschlagen')).toBeInTheDocument()
    expect(screen.getByText('Architektur des Projekts')).toBeInTheDocument()
  })

  // regression guard for #959: leaving the inline rename must not drop focus to <body> -
  // keyboard users would have to tab through the whole page again.
  it('returns focus to the row actions button after cancelling the rename with Escape', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ umbenennen'))
    await user.keyboard('{Escape}')

    const actions = await screen.findByLabelText('Aktionen für Chat „Architektur des Projekts“')
    await waitFor(() => expect(actions).toHaveFocus())
  })

  // regression guard for #959: same for the Enter commit - the button re-mounts under the
  // chat's new title, focus must land on it.
  it('returns focus to the row actions button after committing the rename with Enter', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ umbenennen'))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel{Enter}')

    const actions = await screen.findByLabelText('Aktionen für Chat „Neuer Titel“')
    await waitFor(() => expect(actions).toHaveFocus())
  })

  // A commit via blur means the user deliberately moved focus elsewhere (e.g. a mouse click) -
  // pulling focus back to the actions button would fight that choice.
  it('keeps focus where the user clicked when the rename commits via blur', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ umbenennen'))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel')
    await user.click(screen.getByRole('button', { name: 'Neuer Chat' }))

    expect(await screen.findByText('Neuer Titel')).toBeInTheDocument()
    await screen.findByLabelText('Aktionen für Chat „Neuer Titel“')
    expect(screen.getByRole('button', { name: 'Neuer Chat' })).toHaveFocus()
  })
})
