import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import ChatList from './ChatList'
import type { ChatSummary } from '@/shared/api/types'
import { useChatListStore } from '../stores/chatListStore'
import { useSpaceStore } from '@/features/space'
import { useUiStore } from '@/shared/stores/uiStore'

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

    expect(await screen.findByText('Keine aktiven Chats in diesem Space.')).toBeInTheDocument()
  })

  // "Neu" must not eagerly persist an empty chat - it only navigates to
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Umbenennen' }))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel{Enter}')

    expect(await screen.findByText('Neuer Titel')).toBeInTheDocument()
  })

  it('deletes a chat after confirmation', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, '„Architektur des Projekts“ wirklich löschen?', 'Löschen')

    await waitFor(() => {
      expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    })
  })

  it('does not delete a chat when the confirmation is cancelled', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, '„Architektur des Projekts“ wirklich löschen?', 'Abbrechen')

    expect(screen.getByText('Architektur des Projekts')).toBeInTheDocument()
  })

  // A failed delete must surface to the user, not reject silently (unhandled rejection).
  it('shows an error and keeps the chat when deletion fails on the server', async () => {
    server.use(
      http.delete('/api/v1/chats/:chatId', () => {
        return HttpResponse.json({ error: 'Löschen fehlgeschlagen' }, { status: 500 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, '„Architektur des Projekts“ wirklich löschen?', 'Löschen')

    expect(await screen.findByText('Löschen fehlgeschlagen')).toBeInTheDocument()
    expect(screen.getByText('Architektur des Projekts')).toBeInTheDocument()
  })

  // Same for a failed rename.
  it('shows an error and keeps the old title when renaming fails on the server', async () => {
    server.use(
      http.patch('/api/v1/chats/:chatId', () => {
        return HttpResponse.json({ error: 'Umbenennen fehlgeschlagen' }, { status: 500 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Umbenennen' }))
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Umbenennen' }))
    await user.keyboard('{Escape}')

    const actions = await screen.findByLabelText('Weitere Aktionen für „Architektur des Projekts“')
    await waitFor(() => expect(actions).toHaveFocus())
  })

  // regression guard for #959: same for the Enter commit - the button re-mounts under the
  // chat's new title, focus must land on it.
  it('returns focus to the row actions button after committing the rename with Enter', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Umbenennen' }))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel{Enter}')

    const actions = await screen.findByLabelText('Weitere Aktionen für „Neuer Titel“')
    await waitFor(() => expect(actions).toHaveFocus())
  })

  // A commit via blur means the user deliberately moved focus elsewhere (e.g. a mouse click) -
  // pulling focus back to the actions button would fight that choice.
  it('keeps focus where the user clicked when the rename commits via blur', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    await user.click(screen.getByRole('menuitem', { name: 'Umbenennen' }))
    const field = screen.getByLabelText('Chat-Titel')
    await user.clear(field)
    await user.type(field, 'Neuer Titel')
    await user.click(screen.getByRole('button', { name: 'Neuer Chat' }))

    expect(await screen.findByText('Neuer Titel')).toBeInTheDocument()
    await screen.findByLabelText('Weitere Aktionen für „Neuer Titel“')
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

  it('lists pinned chats above "Zuletzt verwendet" and offers no title filter', () => {
    renderWithProviders(<ChatList spaceId="space-personal" />)

    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(['Angeheftet', 'Zuletzt verwendet'])
    expect(groupTitles('Angeheftet')).toEqual(['Fristen Übersicht'])
    expect(groupTitles('Zuletzt verwendet')).toEqual(['Erlass vom März', 'Rückfrage Kämmerei'])
    expect(screen.queryByRole('searchbox', { name: 'Chats filtern' })).not.toBeInTheDocument()
  })

  it('shows the pin once at the head of the pinned group, not on its rows', () => {
    renderWithProviders(<ChatList spaceId="space-personal" />)

    const heading = screen.getByRole('heading', { level: 3, name: 'Angeheftet' })
    expect(within(heading).getByTestId('PushPinIcon')).toBeInTheDocument()
    const pinnedList = screen.getByRole('list', { name: 'Angeheftet' })
    expect(within(pinnedList).queryByTestId('PushPinIcon')).not.toBeInTheDocument()
  })

  it('always shows the pinned group open, without a collapse toggle', () => {
    renderWithProviders(<ChatList spaceId="space-personal" />)

    expect(screen.getByRole('heading', { level: 3, name: 'Angeheftet' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Angeheftet' })).not.toBeInTheDocument()
    expect(groupTitles('Angeheftet')).toEqual(['Fristen Übersicht'])
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Anheften' }))

    expect(groupTitles('Angeheftet')).toEqual(['Erlass vom März', 'Fristen Übersicht'])
    expect(groupTitles('Zuletzt verwendet')).toEqual(['Rückfrage Kämmerei'])
    await waitFor(() => expect(pinned).toBe('chat-1'))
    await waitFor(() =>
      expect(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“')).toHaveFocus(),
    )
    await waitFor(() =>
      expect(
        useChatListStore
          .getState()
          .chatsBySpaceId['space-personal']?.find((chat) => chat.id === 'chat-1')?.pinnedAt,
      ).toBe('2026-09-18T09:00:00Z'),
    )
  })

  it('unpins a chat back into "Zuletzt verwendet"', async () => {
    let unpinned: string | null = null
    server.use(
      http.delete('/api/v1/chats/:chatId/pin', ({ params }) => {
        unpinned = String(params.chatId)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByLabelText('Weitere Aktionen für „Fristen Übersicht“'))
    await user.click(screen.getByRole('menuitem', { name: 'Lösen' }))

    expect(screen.queryByRole('list', { name: 'Angeheftet' })).not.toBeInTheDocument()
    expect(groupTitles('Zuletzt verwendet')).toEqual([
      'Erlass vom März',
      'Fristen Übersicht',
      'Rückfrage Kämmerei',
    ])
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Anheften' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat nicht gefunden')
    expect(groupTitles('Zuletzt verwendet')).toEqual(['Erlass vom März', 'Rückfrage Kämmerei'])
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Anheften' }))
    await waitFor(() =>
      expect(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“')).toHaveFocus(),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat nicht gefunden')
    expect(groupTitles('Zuletzt verwendet')).toEqual(['Erlass vom März', 'Rückfrage Kämmerei'])
    await waitFor(() =>
      expect(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“')).toHaveFocus(),
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Archivieren' }))

    await waitFor(() => expect(screen.queryByText('Erlass vom März')).not.toBeInTheDocument())
    expect(archived).toBe('chat-1')
    expect(await screen.findByText('Chat „Erlass vom März“ archiviert')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByLabelText('Weitere Aktionen für „Rückfrage Kämmerei“')).toHaveFocus(),
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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Erlass vom März“'))
    await user.click(screen.getByRole('menuitem', { name: 'Archivieren' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat nicht gefunden')
    expect(groupTitles('Zuletzt verwendet')).toEqual(['Erlass vom März', 'Rückfrage Kämmerei'])
  })

  it('opens the chat search with an empty term and no links below the list', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Erlass vom März')

    expect(screen.queryByRole('link', { name: 'Alle Chats' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'In Inhalten suchen' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Chats durchsuchen' }))

    // The term rides along as router state only, never in the address - an empty one lists
    // every chat and puts the cursor into the page's search field.
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats', {
      state: { chatSearchTerm: '' },
    })
  })
})

describe('ChatList "Zuletzt verwendet"', () => {
  function summary(index: number): ChatSummary {
    const updatedAt = new Date(Date.now() - index * 60 * 1000).toISOString()
    return {
      id: `chat-${index}`,
      spaceId: 'space-personal',
      authorId: 'mock-user-id',
      title: `Chat ${index}`,
      useKnowledge: true,
      referencedLibraryIds: [],
      status: 'PRIVATE',
      createdAt: updatedAt,
      updatedAt,
      pinnedAt: null,
    }
  }

  beforeEach(() => {
    mockNavigate.mockReset()
    currentPathname = '/spaces/space-personal'
    useChatListStore.getState().reset()
    useChatListStore.setState({
      chatsBySpaceId: {
        'space-personal': Array.from({ length: 38 }, (_, index) => summary(index + 1)),
      },
      isLoading: false,
      error: null,
    })
  })

  function shownTitles(): string[] {
    return within(screen.getByRole('list', { name: 'Zuletzt verwendet' }))
      .getAllByRole('listitem')
      .map((item) => item.textContent ?? '')
  }

  it('shows 15 chats and loads the rest in pages of 15', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    expect(shownTitles()).toHaveLength(15)
    expect(screen.queryByRole('button', { name: 'Weniger anzeigen' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '15 weitere Chats anzeigen' }))
    expect(shownTitles()).toHaveLength(30)

    // The last page is shorter than a full one and says so.
    await user.click(screen.getByRole('button', { name: '8 weitere Chats anzeigen' }))
    expect(shownTitles()).toHaveLength(38)
    expect(screen.queryByRole('button', { name: /weitere Chats anzeigen/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Weniger anzeigen' }))
    expect(shownTitles()).toHaveLength(15)
  })

  // The sidebar is too narrow for "15 weitere anzeigen" and "weniger anzeigen" side by side -
  // the visible labels stay short and on one line, the accessible names stay complete.
  it('shows short one-line paging labels whose accessible names start with them', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    const more = screen.getByRole('button', { name: '15 weitere Chats anzeigen' })
    expect(more).toHaveTextContent(/^15 weitere Chats anzeigen$/)
    expect(getComputedStyle(more).whiteSpace).toBe('nowrap')

    await user.click(more)
    const fewer = screen.getByRole('button', { name: 'Weniger anzeigen' })
    expect(getComputedStyle(fewer).whiteSpace).toBe('nowrap')
  })

  // regression guard for #2062: the sidebar remounts when the layout switches between desktop
  // and the mobile drawer - the revealed pages must survive that instead of folding back to 15.
  it('keeps the revealed pages when the list remounts', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(<ChatList spaceId="space-personal" />)
    await user.click(screen.getByRole('button', { name: '15 weitere Chats anzeigen' }))
    expect(shownTitles()).toHaveLength(30)

    unmount()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    expect(shownTitles()).toHaveLength(30)
  })

  it('starts a different space at its first page again', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(<ChatList spaceId="space-personal" />)
    await user.click(screen.getByRole('button', { name: '15 weitere Chats anzeigen' }))
    unmount()
    useChatListStore.setState({
      chatsBySpaceId: {
        ...useChatListStore.getState().chatsBySpaceId,
        'space-other': Array.from({ length: 38 }, (_, index) => ({
          ...summary(index + 1),
          id: `other-${index + 1}`,
          spaceId: 'space-other',
        })),
      },
    })

    renderWithProviders(<ChatList spaceId="space-other" />)

    expect(shownTitles()).toHaveLength(15)
  })

  // regression guard for #2062: an open chat beyond the revealed pages - after a remount, or
  // opened by link - is revealed, marked as selected and scrolled into the visible part.
  it('reveals, selects and scrolls to the open chat beyond the first page', () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    try {
      currentPathname = '/spaces/space-personal/chats/chat-35'
      renderWithProviders(<ChatList spaceId="space-personal" />)

      expect(shownTitles()).toHaveLength(38)
      const row = within(screen.getByRole('list', { name: 'Zuletzt verwendet' }))
        .getAllByRole('button')
        .find((element) => element.textContent === 'Chat 35')
      expect(row).toHaveClass('Mui-selected')
      expect(scrollIntoView.mock.contexts).toContain(row)
    } finally {
      // @ts-expect-error jsdom has no scrollIntoView; restore that state
      delete Element.prototype.scrollIntoView
    }
  })

  it('still folds back with "Weniger" while the open chat sits on a later page', async () => {
    const user = userEvent.setup()
    currentPathname = '/spaces/space-personal/chats/chat-20'
    renderWithProviders(<ChatList spaceId="space-personal" />)
    expect(shownTitles()).toHaveLength(30)

    await user.click(screen.getByRole('button', { name: 'Weniger anzeigen' }))

    expect(shownTitles()).toHaveLength(15)
  })

  it('names a last page of one chat in the singular', async () => {
    useChatListStore.setState({
      chatsBySpaceId: {
        'space-personal': Array.from({ length: 16 }, (_, index) => summary(index + 1)),
      },
    })
    renderWithProviders(<ChatList spaceId="space-personal" />)

    expect(screen.getByRole('button', { name: '1 weiteren Chat anzeigen' })).toBeInTheDocument()
  })

  // The button that reveals the last page unmounts with that click - without this, focus would
  // fall to <body> and a keyboard user would stand at the end of the list without a position.
  it('moves focus to the first revealed chat and announces how many came in', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    await user.click(screen.getByRole('button', { name: '15 weitere Chats anzeigen' }))

    // Chat 16 is the first row of the second page (chats are ordered newest first).
    const firstRevealed = within(screen.getByRole('list', { name: 'Zuletzt verwendet' }))
      .getAllByRole('button')
      .find((element) => element.textContent === 'Chat 16')
    expect(firstRevealed).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent('15 weitere Chats angezeigt')

    await user.click(screen.getByRole('button', { name: '8 weitere Chats anzeigen' }))

    expect(document.body).not.toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent('8 weitere Chats angezeigt')

    await user.click(screen.getByRole('button', { name: 'Weniger anzeigen' }))
    expect(screen.getByRole('status')).toHaveTextContent('Wieder 15 Chats angezeigt')
  })
})

describe('ChatList head and groups of the space column (guidelines 5.13)', () => {
  function summary(index: number, pinnedAt: string | null = null): ChatSummary {
    const updatedAt = new Date(Date.now() - index * 60 * 1000).toISOString()
    return {
      id: `chat-${index}`,
      spaceId: 'space-personal',
      authorId: 'mock-user-id',
      title: `Chat ${index}`,
      useKnowledge: true,
      referencedLibraryIds: [],
      status: 'PRIVATE',
      createdAt: updatedAt,
      updatedAt,
      pinnedAt,
    }
  }

  beforeEach(() => {
    mockNavigate.mockReset()
    currentPathname = '/spaces/space-personal'
    useChatListStore.getState().reset()
    useChatListStore.setState({
      chatsBySpaceId: {
        'space-personal': [
          summary(1, '2026-09-01T08:00:00Z'),
          summary(2, '2026-09-02T08:00:00Z'),
          ...Array.from({ length: 20 }, (_, index) => summary(index + 3)),
        ],
      },
      isLoading: false,
      error: null,
    })
  })

  afterEach(() => {
    useUiStore.setState({ collapsedSidebarSections: [] })
    useSpaceStore.setState({ spaces: [] })
  })

  it('offers "Neuer Chat" as a plus icon button with a tooltip, no "Neu" text', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    const create = screen.getByRole('button', { name: 'Neuer Chat' })
    expect(create).not.toHaveTextContent(/Neu/)
    expect(create.querySelector('svg[data-testid="AddIcon"]')).not.toBeNull()

    await user.hover(create)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Neuer Chat')
  })

  it('locks the plus in an archived space and names the reason in its tooltip', async () => {
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Privater Bereich',
          description: null,
          isDefault: true,
          archived: true,
          memberCount: 1,
          memberships: { groupCount: 0, userCount: 1 },
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
    })
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)

    const create = screen.getByRole('button', { name: 'Neuer Chat' })
    expect(create).toBeDisabled()
    await user.hover(create.parentElement as HTMLElement)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'Dieser Space ist archiviert und nimmt keine neuen Chats mehr an',
    )
  })

  it('counts each group in its head, "Zuletzt verwendet" including the chats not yet revealed', () => {
    renderWithProviders(<ChatList spaceId="space-personal" collapsibleGroups />)

    expect(screen.getByRole('button', { name: 'Angeheftet 2' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    // 15 rows are shown, the head counts all 20.
    expect(screen.getByRole('button', { name: 'Zuletzt verwendet 20' })).toBeInTheDocument()
    expect(
      within(screen.getByRole('list', { name: 'Zuletzt verwendet 20' })).getAllByRole('listitem'),
    ).toHaveLength(15)
  })

  it('puts the pin into the folding head of "Angeheftet" without changing its name', () => {
    renderWithProviders(<ChatList spaceId="space-personal" collapsibleGroups />)

    const head = screen.getByRole('button', { name: 'Angeheftet 2' })
    expect(within(head).getByTestId('PushPinIcon')).toBeInTheDocument()
    const rows = screen.getByRole('list', { name: 'Angeheftet 2' })
    expect(within(rows).queryByTestId('PushPinIcon')).not.toBeInTheDocument()
    const recent = screen.getByRole('button', { name: 'Zuletzt verwendet 20' })
    expect(within(recent).queryByTestId('PushPinIcon')).not.toBeInTheDocument()
  })

  it('folds "Angeheftet" like the other groups and remembers it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" collapsibleGroups />)

    const pinned = screen.getByRole('button', { name: 'Angeheftet 2' })
    const region = document.getElementById(pinned.getAttribute('aria-controls') ?? '')
    expect(region).not.toBeNull()

    await user.click(pinned)

    expect(pinned).toHaveAttribute('aria-expanded', 'false')
    expect(region).not.toBeVisible()
    expect(screen.queryByRole('list', { name: 'Angeheftet 2' })).not.toBeInTheDocument()
    expect(useUiStore.getState().collapsedSidebarSections).toEqual(['chats.pinned'])
  })

  // A row pinned into (or unpinned out to) a folded group would not be rendered there, and its
  // focus would fall to the body; the target group opens instead.
  it.each([
    ['pins', 'chats.pinned', 'Chat 3', 'Anheften'],
    ['unpins', 'chats.recent', 'Chat 1', 'Lösen'],
  ])(
    '%s a chat into a folded group, opens that group and keeps focus on the row',
    async (_verb, folded, title, action) => {
      server.use(
        http.put('/api/v1/chats/:chatId/pin', () =>
          HttpResponse.json({ ...summary(3), pinnedAt: '2026-09-18T09:00:00Z' }),
        ),
        http.delete('/api/v1/chats/:chatId/pin', () => new HttpResponse(null, { status: 204 })),
      )
      useUiStore.setState({ collapsedSidebarSections: [folded] })
      const user = userEvent.setup()
      renderWithProviders(<ChatList spaceId="space-personal" collapsibleGroups />)

      await user.click(screen.getByLabelText(`Weitere Aktionen für „${title}“`))
      await user.click(screen.getByRole('menuitem', { name: action }))

      await waitFor(() =>
        expect(screen.getByLabelText(`Weitere Aktionen für „${title}“`)).toHaveFocus(),
      )
      expect(useUiStore.getState().collapsedSidebarSections).toEqual([])
    },
  )

  it('folds "Zuletzt verwendet" together with its paging buttons', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" collapsibleGroups />)
    expect(screen.getByRole('button', { name: '5 weitere Chats anzeigen' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Zuletzt verwendet 20' }))

    expect(screen.queryByRole('list', { name: 'Zuletzt verwendet 20' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '5 weitere Chats anzeigen' }),
    ).not.toBeInTheDocument()
  })
})
