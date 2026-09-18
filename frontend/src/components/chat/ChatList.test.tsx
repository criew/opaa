import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
import ChatList from './ChatList'
import type { ChatSummary } from '../../types/api'
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
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))
    await answerConfirm(user, '„Architektur des Projekts“ wirklich löschen?', 'Löschen')

    await waitFor(() => {
      expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    })
  })

  it('does not delete a chat when the confirmation is cancelled', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))
    await answerConfirm(user, '„Architektur des Projekts“ wirklich löschen?', 'Abbrechen')

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
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    await user.click(screen.getByLabelText('Chat „Architektur des Projekts“ löschen'))
    await answerConfirm(user, '„Architektur des Projekts“ wirklich löschen?', 'Löschen')

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

describe('ChatList ordering', () => {
  const DAY_MS = 24 * 60 * 60 * 1000

  function summary(id: string, title: string, ageMs: number, pinnedAt?: string): ChatSummary {
    const updatedAt = new Date(Date.now() - ageMs).toISOString()
    return {
      id,
      spaceId: 'space-personal',
      authorId: 'mock-user-id',
      title,
      useKnowledge: true,
      referencedLibraryIds: [],
      status: 'PRIVATE',
      createdAt: updatedAt,
      updatedAt,
      pinnedAt: pinnedAt ?? null,
    }
  }

  beforeEach(() => {
    mockNavigate.mockReset()
    currentPathname = '/spaces/space-personal'
    useChatListStore.setState({
      chatsBySpaceId: {
        'space-personal': [
          summary('chat-1', 'Erlass vom März', 60 * 1000),
          summary('chat-2', 'Rückfrage Kämmerei', 400 * DAY_MS),
          summary('chat-3', 'Fristen Übersicht', 90 * DAY_MS, '2026-09-01T08:00:00Z'),
        ],
      },
      isLoading: false,
      error: null,
    })
  })

  function groupTitles(groupName: string): string[] {
    return within(screen.getByRole('list', { name: groupName }))
      .getAllByRole('listitem')
      .map((item) => item.textContent ?? '')
  }

  it('groups the chats under headings, pinned chats first', () => {
    renderWithProviders(<ChatList spaceId="space-personal" />)

    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(['Angeheftet', 'Heute', 'Älter'])
    expect(groupTitles('Angeheftet')).toEqual(['Fristen Übersicht'])
    expect(groupTitles('Heute')).toEqual(['Erlass vom März'])
    expect(groupTitles('Älter')).toEqual(['Rückfrage Kämmerei'])
  })

  it('filters by title while typing, announces the count and restores the list on Escape', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    const field = screen.getByRole('searchbox', { name: 'Chats filtern' })

    await user.type(field, 'KÄMMER')

    expect(screen.getByText('Rückfrage Kämmerei')).toBeInTheDocument()
    expect(screen.queryByText('Erlass vom März')).not.toBeInTheDocument()
    expect(screen.queryByText('Fristen Übersicht')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('1 Chat gefunden')

    await user.keyboard('{Escape}')

    expect(field).toHaveValue('')
    expect(screen.getByText('Erlass vom März')).toBeInTheDocument()
    expect(screen.getByText('Fristen Übersicht')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('')
  })

  it('says so when no title matches', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.type(screen.getByRole('searchbox', { name: 'Chats filtern' }), 'Haushalt')

    expect(screen.getByRole('status')).toHaveTextContent('Kein Chat mit diesem Titel')
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByRole('heading', { level: 3 })).not.toBeInTheDocument()
  })

  it('collapses and expands the pinned group without touching the others', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    const toggle = screen.getByRole('button', { name: 'Angeheftet' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Fristen Übersicht')).not.toBeInTheDocument()
    expect(screen.getByText('Erlass vom März')).toBeInTheDocument()

    await user.click(toggle)
    expect(screen.getByText('Fristen Übersicht')).toBeInTheDocument()
  })

  it('pins a chat via the context menu and keeps focus on its actions button', async () => {
    let pinned: string | null = null
    server.use(
      http.put('/api/v1/chats/:chatId/pin', ({ params }) => {
        pinned = String(params.chatId)
        return HttpResponse.json({
          ...summary('chat-1', 'Erlass vom März', 60 * 1000),
          pinnedAt: '2026-09-18T09:00:00Z',
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Aktionen für Chat „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Erlass vom März“ anheften' }))

    expect(groupTitles('Angeheftet')).toEqual(['Erlass vom März', 'Fristen Übersicht'])
    expect(screen.queryByRole('list', { name: 'Heute' })).not.toBeInTheDocument()
    await waitFor(() => expect(pinned).toBe('chat-1'))
    await waitFor(() =>
      expect(screen.getByLabelText('Aktionen für Chat „Erlass vom März“')).toHaveFocus(),
    )
    await waitFor(() =>
      expect(
        useChatListStore
          .getState()
          .chatsBySpaceId['space-personal']?.find((chat) => chat.id === 'chat-1')?.pinnedAt,
      ).toBe('2026-09-18T09:00:00Z'),
    )
  })

  it('expands a collapsed pinned group when a chat is pinned into it', async () => {
    server.use(
      http.put('/api/v1/chats/:chatId/pin', () =>
        HttpResponse.json({
          ...summary('chat-1', 'Erlass vom März', 60 * 1000),
          pinnedAt: '2026-09-18T09:00:00Z',
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    const toggle = screen.getByRole('button', { name: 'Angeheftet' })
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await user.click(screen.getByLabelText('Aktionen für Chat „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Erlass vom März“ anheften' }))

    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(groupTitles('Angeheftet')).toEqual(['Erlass vom März', 'Fristen Übersicht'])
    await waitFor(() =>
      expect(screen.getByLabelText('Aktionen für Chat „Erlass vom März“')).toHaveFocus(),
    )
  })

  it('unpins a chat back into its time group', async () => {
    let unpinned: string | null = null
    server.use(
      http.delete('/api/v1/chats/:chatId/pin', ({ params }) => {
        unpinned = String(params.chatId)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Aktionen für Chat „Fristen Übersicht“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Fristen Übersicht“ lösen' }))

    expect(screen.queryByRole('list', { name: 'Angeheftet' })).not.toBeInTheDocument()
    expect(groupTitles('Älter')).toEqual(['Fristen Übersicht', 'Rückfrage Kämmerei'])
    await waitFor(() => expect(unpinned).toBe('chat-3'))
  })

  it('rolls the pin back and shows the error when the server rejects it', async () => {
    server.use(
      http.put('/api/v1/chats/:chatId/pin', () =>
        HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Aktionen für Chat „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Erlass vom März“ anheften' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat nicht gefunden')
    expect(groupTitles('Heute')).toEqual(['Erlass vom März'])
    expect(groupTitles('Angeheftet')).toEqual(['Fristen Übersicht'])
  })

  // The rollback moves the row back into its time group; focus must go with it instead of
  // falling to the page body.
  it('keeps focus on the row when a pin is rolled back', async () => {
    server.use(
      http.put('/api/v1/chats/:chatId/pin', async () => {
        await new Promise((resolve) => setTimeout(resolve, 30))
        return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Aktionen für Chat „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Erlass vom März“ anheften' }))
    await waitFor(() =>
      expect(screen.getByLabelText('Aktionen für Chat „Erlass vom März“')).toHaveFocus(),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat nicht gefunden')
    expect(groupTitles('Heute')).toEqual(['Erlass vom März'])
    await waitFor(() =>
      expect(screen.getByLabelText('Aktionen für Chat „Erlass vom März“')).toHaveFocus(),
    )
  })

  it('archives a chat via the context menu and moves focus to the next row', async () => {
    let archived: string | null = null
    server.use(
      http.put('/api/v1/chats/:chatId/archive', ({ params }) => {
        archived = String(params.chatId)
        return HttpResponse.json({
          ...summary('chat-1', 'Erlass vom März', 60 * 1000),
          archivedAt: '2026-09-18T09:00:00Z',
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Aktionen für Chat „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Erlass vom März“ archivieren' }))

    await waitFor(() => expect(screen.queryByText('Erlass vom März')).not.toBeInTheDocument())
    expect(archived).toBe('chat-1')
    expect(await screen.findByText('Chat „Erlass vom März“ archiviert')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByLabelText('Aktionen für Chat „Rückfrage Kämmerei“')).toHaveFocus(),
    )
  })

  it('keeps the chat and shows the error when archiving fails', async () => {
    server.use(
      http.put('/api/v1/chats/:chatId/archive', () =>
        HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Aktionen für Chat „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Chat „Erlass vom März“ archivieren' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat nicht gefunden')
    expect(groupTitles('Heute')).toEqual(['Erlass vom März'])
  })

  it('links to the page "Chats" below the list, also when the filter finds nothing', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.type(screen.getByRole('searchbox', { name: 'Chats filtern' }), 'Haushalt')
    const link = screen.getByRole('link', { name: 'Alle Chats' })
    expect(link).toHaveAttribute('href', '/spaces/space-personal/chats')

    await user.click(link)
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats')
  })
})
