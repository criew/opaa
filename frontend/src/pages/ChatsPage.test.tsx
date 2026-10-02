import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi, beforeEach } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import ChatsPage from './ChatsPage'
import { useChatListStore } from '../stores/chatListStore'
import { useSpaceStore } from '../stores/spaceStore'
import {
  mockArchivedChatsForSpace,
  mockChatArchive,
  mockChatDetails,
  mockSearchChats,
} from '../mocks/chatFixtures'
import { server } from '../mocks/server'
import type { ChatSearchRequest, ChatSearchResponse, ChatSummary } from '../types/api'

const mockNavigate = vi.fn()
let currentLocation: { pathname: string; state: unknown; key: string } = {
  pathname: '/spaces/space-personal/chats',
  state: null,
  key: 'initial',
}

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: 'space-personal' }),
    useNavigate: () => mockNavigate,
    useLocation: () => currentLocation,
  }
})

const SEARCH_URL = '/api/v1/spaces/space-personal/chats/search'

interface CapturedSearch {
  url: string
  body: ChatSearchRequest
}

/** Answers the search from the mock data and records every request as it went over the wire. */
function captureSearches(
  answer: (body: ChatSearchRequest) => ChatSearchResponse = (body) =>
    mockSearchChats('space-personal', body),
): CapturedSearch[] {
  const captured: CapturedSearch[] = []
  server.use(
    http.post(SEARCH_URL, async ({ request }) => {
      const body = (await request.json()) as ChatSearchRequest
      captured.push({ url: request.url, body })
      return HttpResponse.json(answer(body))
    }),
  )
  return captured
}

function searchField(): HTMLElement {
  return screen.getByRole('searchbox', { name: 'In Chats suchen' })
}

function hitList(): HTMLElement {
  return screen.getByRole('list', { name: 'Suchtreffer' })
}

/** A term found only in the answer of "Deployment-Fragen". */
const ANSWER_TERM = 'Compose'

function putTermIntoAnswer() {
  mockChatDetails['chat-personal-2'].messages[1].content =
    'Das Deployment läuft über Docker Compose; <b>Neustart</b> nach jeder Änderung.'
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** jsdom has no matchMedia; the table renders only on a desktop viewport (guidelines 5.3). */
function matchMediaAt(desktop: boolean) {
  return (query: string): MediaQueryList =>
    ({
      matches: desktop && query.includes('min-width'),
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }) as unknown as MediaQueryList
}

function manyChats(count: number): ChatSummary[] {
  return Array.from({ length: count }, (_, index) => {
    const updatedAt = new Date(Date.UTC(2026, 8, 1) - index * 60_000).toISOString()
    return {
      id: `many-${index + 1}`,
      spaceId: 'space-personal',
      authorId: 'mock-user-id',
      title: `Chat ${index + 1}`,
      useKnowledge: true,
      referencedLibraryIds: [],
      status: 'PRIVATE',
      createdAt: updatedAt,
      updatedAt,
      pinnedAt: null,
    }
  })
}

async function openRowMenu(user: ReturnType<typeof userEvent.setup>, title: string) {
  await user.click(await screen.findByRole('button', { name: `Aktionen für „${title}“` }))
  return screen.findByRole('menu', { name: `Aktionen für „${title}“` })
}

function rowTitles(): string[] {
  const table = screen.getByRole('table')
  return within(table)
    .getAllByRole('link')
    .map((link) => link.textContent ?? '')
}

describe('ChatsPage', () => {
  const originalMatchMedia = window.matchMedia
  beforeAll(() => {
    window.matchMedia = matchMediaAt(true)
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    mockNavigate.mockReset()
    currentLocation = { pathname: '/spaces/space-personal/chats', state: null, key: 'initial' }
    useChatListStore.setState({
      chatsBySpaceId: {},
      archiveBySpaceId: {},
      isLoading: false,
      isLoadingArchive: false,
      error: null,
    })
    useSpaceStore.setState({ spaces: [] })
  })

  it('shows the space name and both tabs with their counts', async () => {
    mockChatArchive['chat-personal-1'] = '2026-09-18T09:00:00Z'
    renderWithProviders(<ChatsPage />)

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Chats in „Meine Dokumente“' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'Aktiv (1)' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(await screen.findByRole('tab', { name: 'Archiv (1)' })).toBeInTheDocument()
    expect(rowTitles()).toEqual(['Deployment-Fragen'])
  })

  it('offers the bulk actions only once a chat is selected', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    const toolbar = screen.getByRole('toolbar', { name: 'Sammelaktionen' })
    expect(within(toolbar).queryByRole('button', { name: 'Archivieren' })).not.toBeInTheDocument()
    expect(within(toolbar).queryByRole('button', { name: 'Löschen' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: '„Architektur des Projekts“ auswählen' }))

    expect(within(toolbar).getByRole('button', { name: 'Archivieren' })).toBeEnabled()
    expect(within(toolbar).getByRole('button', { name: 'Löschen' })).toBeEnabled()
    expect(screen.getByText('1 ausgewählt')).toBeInTheDocument()
  })

  it('archives a single chat from its row menu', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    const menu = await openRowMenu(user, 'Deployment-Fragen')
    await user.click(within(menu).getByRole('menuitem', { name: 'Archivieren' }))

    expect(await screen.findByRole('status')).toHaveTextContent('1 Chat archiviert')
    await waitFor(() => expect(rowTitles()).toEqual(['Architektur des Projekts']))
    expect(Object.keys(mockChatArchive)).toEqual(['chat-personal-2'])
  })

  it('deletes a single chat from its row menu after a confirmation that names it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    const menu = await openRowMenu(user, 'Deployment-Fragen')
    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, '„Deployment-Fragen“ wirklich löschen?', 'Löschen')

    expect(await screen.findByRole('status')).toHaveTextContent('1 Chat gelöscht')
    expect(mockChatDetails['chat-personal-2']).toBeUndefined()
  })

  it('offers "Zurückholen" instead of pinning and archiving in the archive tab', async () => {
    mockChatArchive['chat-personal-1'] = '2026-09-18T09:00:00Z'
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await user.click(await screen.findByRole('tab', { name: 'Archiv (1)' }))

    const menu = await openRowMenu(user, 'Architektur des Projekts')

    expect(within(menu).getByRole('menuitem', { name: 'Zurückholen' })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: 'Anheften' })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: 'Archivieren' })).not.toBeInTheDocument()
  })

  it('pages the active chats by 25 with the pager of the administration lists', async () => {
    useChatListStore.setState({ chatsBySpaceId: { 'space-personal': manyChats(30) } })
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (30)' })

    expect(rowTitles()).toHaveLength(25)
    expect(screen.getByText('30 Chats · Seite 1 von 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Vorherige Seite' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Nächste Seite' }))

    expect(rowTitles()).toEqual(['Chat 26', 'Chat 27', 'Chat 28', 'Chat 29', 'Chat 30'])
    expect(screen.getByText('30 Chats · Seite 2 von 2')).toBeInTheDocument()
  })

  it('marks a pinned chat in its title cell and pins another from the row menu', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })
    expect(screen.queryByText('angeheftet')).not.toBeInTheDocument()

    const menu = await openRowMenu(user, 'Architektur des Projekts')
    await user.click(within(menu).getByRole('menuitem', { name: 'Anheften' }))

    expect(await screen.findByText('angeheftet')).toBeInTheDocument()
    expect(rowTitles()[0]).toBe('Architektur des Projekts')
  })

  it('archives every chat of the page by keyboard and announces the result', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' }).focus()
    await user.keyboard(' ')
    expect(screen.getByText('2 ausgewählt')).toBeInTheDocument()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Archivieren' })).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('status')).toHaveTextContent('2 Chats archiviert')
    expect(await screen.findByRole('tab', { name: 'Aktiv (0)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Archiv (2)' })).toBeInTheDocument()
    expect(screen.getByText('Keine aktiven Chats in diesem Space.')).toBeInTheDocument()
    expect(Object.keys(mockChatArchive).sort()).toEqual(['chat-personal-1', 'chat-personal-2'])
    // Nothing left to select on this tab: focus lands on the tab rather than on the page body.
    expect(screen.getByRole('tab', { name: 'Aktiv (0)' })).toHaveFocus()
  })

  it('brings a chat back from the archive tab', async () => {
    mockChatArchive['chat-personal-1'] = '2026-09-18T09:00:00Z'
    mockChatArchive['chat-personal-2'] = '2026-09-18T10:00:00Z'
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)

    await user.click(await screen.findByRole('tab', { name: 'Archiv (2)' }))
    await waitFor(() =>
      expect(rowTitles()).toEqual(['Deployment-Fragen', 'Architektur des Projekts']),
    )
    expect(screen.getByRole('columnheader', { name: 'Archiviert am' })).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: '„Deployment-Fragen“ auswählen' }))
    await user.click(screen.getByRole('button', { name: 'Zurückholen' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '1 Chat aus dem Archiv zurückgeholt',
    )
    await waitFor(() => expect(rowTitles()).toEqual(['Architektur des Projekts']))
    expect(screen.getByRole('tab', { name: 'Aktiv (1)' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' })).toHaveFocus()
  })

  it('#1923: shows at an archived chat when the automatic cleanup deletes it', async () => {
    mockChatArchive['chat-personal-1'] = '2026-09-18T09:00:00Z'
    server.use(
      http.get('/api/v1/spaces/:spaceId/chats/archived', () =>
        HttpResponse.json({
          items: mockArchivedChatsForSpace('space-personal').map((chat) => ({
            ...chat,
            deletionDueAt: '2027-09-18T09:00:00Z',
          })),
          page: 0,
          size: 25,
          totalElements: 1,
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)

    await user.click(await screen.findByRole('tab', { name: 'Archiv (1)' }))

    expect(await screen.findByText('Wird am 18.09.2027 gelöscht')).toBeInTheDocument()
  })

  it('deletes the selected chats after a confirmation that names their number', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    await user.click(screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' }))
    await user.click(screen.getByRole('button', { name: 'Löschen' }))
    await answerConfirm(user, '2 Chats wirklich löschen?', 'Löschen')

    expect(await screen.findByRole('status')).toHaveTextContent('2 Chats gelöscht')
    expect(mockChatDetails['chat-personal-1']).toBeUndefined()
    expect(mockChatDetails['chat-personal-2']).toBeUndefined()
  })

  describe('chat search', () => {
    afterEach(() => {
      vi.restoreAllMocks()
    })

    it('finds a term of an answer and shows the chat with its highlighted excerpt', async () => {
      putTermIntoAnswer()
      const captured = captureSearches()
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), ANSWER_TERM)

      const hit = await screen.findByRole('link', { name: 'Deployment-Fragen' })
      expect(hit).toHaveAttribute(
        'href',
        '/spaces/space-personal/chats/chat-personal-2?message=message-personal-2-2',
      )
      const item = within(hitList()).getByRole('listitem')
      expect(item).toHaveTextContent('Antwort')
      expect(item).toHaveTextContent('06.03.2026')
      // Markup in a message is literal text of the excerpt, never an element.
      expect(item).toHaveTextContent('<b>Neustart</b>')
      expect(item.querySelector('b')).toBeNull()
      expect([...item.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual([
        'Compose',
      ])
      // The result list replaces the tabs while a term is entered.
      expect(screen.queryByRole('tab')).not.toBeInTheDocument()
      // The term travels in the body only - one request after the typing pause, not per keystroke.
      expect(captured).toHaveLength(1)
      expect(captured[0].body).toEqual({ query: ANSWER_TERM, page: 0, pageSize: 20 })
      expect(captured[0].url).not.toContain(ANSWER_TERM)
      expect(captured[0].url).not.toContain('?')
    })

    it('searches at once on Enter, without waiting for the typing pause', async () => {
      putTermIntoAnswer()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      fireEvent.change(searchField(), { target: { value: ANSWER_TERM } })
      expect(screen.queryByRole('progressbar', { name: 'Suche läuft' })).not.toBeInTheDocument()
      fireEvent.keyDown(searchField(), { key: 'Enter' })

      expect(screen.getByRole('progressbar', { name: 'Suche läuft' })).toBeInTheDocument()
      expect(await screen.findByRole('link', { name: 'Deployment-Fragen' })).toBeInTheDocument()
    })

    it('restores the tabs once the field is cleared', async () => {
      putTermIntoAnswer()
      captureSearches()
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), ANSWER_TERM)
      await screen.findByRole('list', { name: 'Suchtreffer' })
      await user.clear(searchField())

      expect(await screen.findByRole('tab', { name: 'Aktiv (2)' })).toBeInTheDocument()
      expect(screen.queryByRole('list', { name: 'Suchtreffer' })).not.toBeInTheDocument()
    })

    it('asks for a longer term below the minimum length and does not search', async () => {
      const captured = captureSearches()
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), 'ab{Enter}')
      await wait(500)

      expect(screen.getByText('Bitte mindestens 3 Zeichen eingeben.')).toBeInTheDocument()
      expect(captured).toHaveLength(0)
    })

    it('marks a hit in an archived chat', async () => {
      putTermIntoAnswer()
      mockChatArchive['chat-personal-2'] = '2026-09-18T09:00:00Z'
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Archiv (1)' })

      await user.type(searchField(), ANSWER_TERM)

      await screen.findByRole('link', { name: 'Deployment-Fragen' })
      expect(within(hitList()).getByRole('listitem')).toHaveTextContent('Archiviert')
    })

    it('says that no chat contains the term and which chats were searched', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), 'Haushaltsplan')

      expect(await screen.findByText('Kein Chat enthält „Haushaltsplan“.')).toBeInTheDocument()
      expect(
        screen.getByText(/Durchsucht werden nur Ihre eigenen Chats in diesem Space/),
      ).toBeInTheDocument()
    })

    it('loads further hits on request and moves the focus to the first new one', async () => {
      const hit = (chatId: string, title: string) => ({
        chatId,
        title,
        archivedAt: null,
        messageId: `${chatId}-m`,
        role: 'USER' as const,
        messageCreatedAt: '2026-09-01T10:00:00Z',
        excerpt: `Frist in ${title}`,
        highlights: [{ start: 0, end: 5 }],
      })
      const captured = captureSearches((body) =>
        body.page === 0
          ? { hits: [hit('chat-a', 'Erster Chat')], hasMore: true }
          : { hits: [hit('chat-b', 'Zweiter Chat')], hasMore: false },
      )
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), 'Frist{Enter}')
      await screen.findByRole('link', { name: 'Erster Chat' })
      await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

      const second = await screen.findByRole('link', { name: 'Zweiter Chat' })
      expect(second).toHaveFocus()
      expect(screen.getByRole('link', { name: 'Erster Chat' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Weitere laden' })).not.toBeInTheDocument()
      expect(captured.map((request) => request.body.page)).toEqual([0, 1])
      expect(within(hitList()).getAllByRole('listitem')[1]).toHaveTextContent('Frage')
    })

    it('keeps the status region in place across searches so every result is announced', async () => {
      putTermIntoAnswer()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      fireEvent.change(searchField(), { target: { value: ANSWER_TERM } })
      const region = screen.getByRole('region', { name: 'Suchtreffer der Chatsuche' })
      const status = within(region).getByRole('status')
      expect(status).toHaveTextContent('')
      fireEvent.keyDown(searchField(), { key: 'Enter' })

      await waitFor(() => expect(status).toHaveTextContent('1 Chat gefunden'))
      fireEvent.change(searchField(), { target: { value: 'Haushaltsplan' } })
      fireEvent.keyDown(searchField(), { key: 'Enter' })
      await waitFor(() => expect(status).toHaveTextContent('Keine Treffer'))
      expect(within(region).getByRole('status')).toBe(status)
    })

    it('loads further hits again after a failed page and lists a chat only once', async () => {
      const hit = (chatId: string, title: string) => ({
        chatId,
        title,
        archivedAt: null,
        messageId: `${chatId}-m`,
        role: 'USER' as const,
        messageCreatedAt: '2026-09-01T10:00:00Z',
        excerpt: `Frist in ${title}`,
        highlights: [{ start: 0, end: 5 }],
      })
      let failNextPage = true
      server.use(
        http.post(SEARCH_URL, async ({ request }) => {
          const body = (await request.json()) as ChatSearchRequest
          if (body.page === 0) {
            return HttpResponse.json({ hits: [hit('chat-a', 'Erster Chat')], hasMore: true })
          }
          if (failNextPage) {
            failNextPage = false
            return new HttpResponse('boom', { status: 500 })
          }
          return HttpResponse.json({
            hits: [hit('chat-a', 'Erster Chat'), hit('chat-b', 'Zweiter Chat')],
            hasMore: false,
          })
        }),
      )
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), 'Frist{Enter}')
      await screen.findByRole('link', { name: 'Erster Chat' })
      await user.click(screen.getByRole('button', { name: 'Weitere laden' }))
      expect(
        await screen.findByText('Die Chatsuche ist fehlgeschlagen. Bitte später erneut versuchen.'),
      ).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

      expect(await screen.findByRole('link', { name: 'Zweiter Chat' })).toBeInTheDocument()
      expect(within(hitList()).getAllByRole('listitem')).toHaveLength(2)
      expect(
        screen.queryByText('Die Chatsuche ist fehlgeschlagen. Bitte später erneut versuchen.'),
      ).not.toBeInTheDocument()
    })

    it('explains the rate limit with the waiting time', async () => {
      server.use(
        http.post(SEARCH_URL, () =>
          HttpResponse.json(
            { error: 'Zu viele Anfragen', status: 429 },
            { status: 429, headers: { 'Retry-After': '30' } },
          ),
        ),
      )
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), 'Frist{Enter}')

      expect(
        await screen.findByText(
          'Zu viele Suchanfragen in kurzer Zeit. Bitte in 30 Sekunden erneut versuchen.',
        ),
      ).toBeInTheDocument()
    })

    it('reports a failed search as a German message', async () => {
      server.use(http.post(SEARCH_URL, () => new HttpResponse('boom', { status: 500 })))
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), 'Frist{Enter}')

      expect(
        await screen.findByText('Die Chatsuche ist fehlgeschlagen. Bitte später erneut versuchen.'),
      ).toBeInTheDocument()
    })

    it('shows only the answer to the latest term when an older search answers late', async () => {
      putTermIntoAnswer()
      let releaseSlow: () => void = () => {}
      server.use(
        http.post(SEARCH_URL, async ({ request }) => {
          const body = (await request.json()) as ChatSearchRequest
          if (body.query === 'Projekt') {
            await new Promise<void>((resolve) => {
              releaseSlow = resolve
            })
          }
          return HttpResponse.json(mockSearchChats('space-personal', body))
        }),
      )
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      fireEvent.change(searchField(), { target: { value: 'Projekt' } })
      fireEvent.keyDown(searchField(), { key: 'Enter' })
      fireEvent.change(searchField(), { target: { value: ANSWER_TERM } })
      fireEvent.keyDown(searchField(), { key: 'Enter' })
      await screen.findByRole('link', { name: 'Deployment-Fragen' })
      releaseSlow()
      await wait(50)

      expect(
        screen.queryByRole('link', { name: 'Architektur des Projekts' }),
      ).not.toBeInTheDocument()
      expect(within(hitList()).getAllByRole('listitem')).toHaveLength(1)
    })

    it('opens a hit at its message', async () => {
      putTermIntoAnswer()
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), ANSWER_TERM)
      await user.click(await screen.findByRole('link', { name: 'Deployment-Fragen' }))

      expect(mockNavigate).toHaveBeenCalledWith(
        '/spaces/space-personal/chats/chat-personal-2?message=message-personal-2-2',
      )
    })

    it('takes over the term from the sidebar, searches it and drops it from the history entry', async () => {
      putTermIntoAnswer()
      currentLocation = {
        pathname: '/spaces/space-personal/chats',
        state: { chatSearchTerm: ANSWER_TERM },
        key: 'from-sidebar',
      }
      renderWithProviders(<ChatsPage />)

      expect(await screen.findByRole('link', { name: 'Deployment-Fragen' })).toBeInTheDocument()
      expect(searchField()).toHaveValue(ANSWER_TERM)
      expect(searchField()).toHaveFocus()
      // A reload must not bring the term back: the history entry loses its state right away.
      expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats', {
        replace: true,
        state: null,
      })
    })

    it('never writes the term into the browser storage', async () => {
      putTermIntoAnswer()
      const setItem = vi.spyOn(Storage.prototype, 'setItem')
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await screen.findByRole('tab', { name: 'Aktiv (2)' })

      await user.type(searchField(), `${ANSWER_TERM}{Enter}`)
      await screen.findByRole('link', { name: 'Deployment-Fragen' })

      const written = setItem.mock.calls.map(([key, value]) => `${key}=${value}`)
      expect(written.filter((entry) => entry.includes(ANSWER_TERM))).toEqual([])
      expect(JSON.stringify({ ...localStorage })).not.toContain(ANSWER_TERM)
      expect(JSON.stringify({ ...sessionStorage })).not.toContain(ANSWER_TERM)
    })

    it('offers the search on the archive tab as well', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ChatsPage />)
      await user.click(await screen.findByRole('tab', { name: /^Archiv/ }))

      expect(searchField()).toBeInTheDocument()
    })
  })

  it('opens a chat from its title', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    await user.click(screen.getByRole('link', { name: 'Deployment-Fragen' }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/chat-personal-2')
  })
})

describe('ChatsPage below tablet width', () => {
  const originalMatchMedia = window.matchMedia
  beforeAll(() => {
    window.matchMedia = matchMediaAt(false)
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    useChatListStore.setState({
      chatsBySpaceId: {},
      archiveBySpaceId: {},
      isLoading: false,
      isLoadingArchive: false,
      error: null,
    })
  })

  it('lists the chats as entries instead of a table (guidelines 5.3)', async () => {
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    const entry = screen.getByRole('article', { name: 'Deployment-Fragen' })
    expect(within(entry).getByRole('link', { name: 'Deployment-Fragen' })).toBeInTheDocument()
    expect(
      within(entry).getByRole('checkbox', { name: '„Deployment-Fragen“ auswählen' }),
    ).toBeInTheDocument()
    expect(
      within(entry).getByRole('button', { name: 'Aktionen für „Deployment-Fragen“' }),
    ).toBeInTheDocument()
  })
})
