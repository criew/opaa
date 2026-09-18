import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import ChatsPage from './ChatsPage'
import { useChatListStore } from '../stores/chatListStore'
import { useSpaceStore } from '../stores/spaceStore'
import { mockChatArchive, mockChatDetails } from '../mocks/fixtures'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: 'space-personal' }),
    useNavigate: () => mockNavigate,
  }
})

function rowTitles(): string[] {
  const table = screen.getByRole('table')
  return within(table)
    .getAllByRole('link')
    .map((link) => link.textContent ?? '')
}

describe('ChatsPage', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
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

    const archive = screen.getByRole('button', { name: 'Archivieren' })
    expect(archive).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Löschen' })).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: '„Architektur des Projekts“ auswählen' }))

    expect(archive).toBeEnabled()
    expect(screen.getByText('1 ausgewählt')).toBeInTheDocument()
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
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
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

  it('filters the active chats by title', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    await user.type(screen.getByRole('searchbox', { name: 'Chats nach Titel filtern' }), 'deploy')

    expect(rowTitles()).toEqual(['Deployment-Fragen'])
  })

  it('opens a chat from its title', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatsPage />)
    await screen.findByRole('tab', { name: 'Aktiv (2)' })

    await user.click(screen.getByRole('link', { name: 'Deployment-Fragen' }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/chat-personal-2')
  })
})
