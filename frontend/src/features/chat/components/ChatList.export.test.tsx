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

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    const item = screen.getByRole('menuitem', {
      name: `Exportieren als ${label}`,
    })
    expect(item).toHaveTextContent(`Exportieren als ${label}`)
    await user.click(item)

    expect(exportChat).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'chat-personal-1', title: 'Architektur des Projekts' }),
      format,
    )
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  // regression guard for #3211: axe label-content-name-mismatch (WCAG 2.5.3 "Label in Name")
  // requires the visible text of every entry to be part of its accessible name.
  it('keeps the visible text of every chat menu entry in its accessible name', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatList spaceId="space-personal" />)
    await screen.findByText('Architektur des Projekts')

    await user.click(screen.getByLabelText('Weitere Aktionen für „Architektur des Projekts“'))
    const items = screen.getAllByRole('menuitem')

    expect(items).toHaveLength(6)
    for (const item of items) {
      const visibleText = (item.textContent ?? '').trim()
      expect(visibleText).not.toBe('')
      expect(item).toHaveAccessibleName(new RegExp(escapeRegExp(visibleText), 'i'))
    }
  })
})

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
