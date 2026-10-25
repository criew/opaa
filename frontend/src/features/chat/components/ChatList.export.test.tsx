import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { useChatListStore } from '../stores/chatListStore'
import ChatList from './ChatList'

const exportChat = vi.fn()

vi.mock('./exportChat', () => ({
  exportChat: (...args: unknown[]) => exportChat(...args) as Promise<void>,
}))

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => vi.fn(),
    useLocation: () => ({ pathname: '/spaces/space-personal' }),
  }
})

describe('ChatList export actions', () => {
  beforeEach(() => {
    exportChat.mockReset()
    exportChat.mockResolvedValue(undefined)
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it.each([
    ['Markdown', 'markdown'],
    ['PDF', 'pdf'],
  ])('offers "Exportieren als %s" in the chat menu', async (label, format) => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Aktionen für Chat „Architektur des Projekts“'))
    const item = screen.getByRole('menuitem', {
      name: `Chat „Architektur des Projekts“ als ${label} exportieren`,
    })
    expect(item).toHaveTextContent(`Exportieren als ${label}`)
    await user.click(item)

    expect(exportChat).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'chat-personal-1', title: 'Architektur des Projekts' }),
      format,
    )
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
})
