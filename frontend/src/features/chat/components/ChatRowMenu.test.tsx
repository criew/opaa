import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { mockChatDetails, toChatSummary } from '../mocks/chatFixtures'
import ChatRowMenu from './ChatRowMenu'

const exportChat = vi.fn()

vi.mock('./exportChat', () => ({
  exportChat: (...args: unknown[]) => exportChat(...args) as Promise<void>,
}))

describe('ChatRowMenu', () => {
  beforeEach(() => {
    exportChat.mockReset()
    exportChat.mockResolvedValue(undefined)
  })

  it.each([
    [false, 'Markdown', 'markdown'],
    [false, 'PDF', 'pdf'],
    [true, 'Markdown', 'markdown'],
    [true, 'PDF', 'pdf'],
  ])('archived=%s: "Exportieren als %s" exports the chat', async (archived, label, format) => {
    const user = userEvent.setup()
    const chat = toChatSummary(mockChatDetails['chat-personal-1'])
    renderWithProviders(
      <ChatRowMenu chat={chat} archived={archived} onAction={vi.fn()} onPin={vi.fn()} />,
    )

    await user.click(screen.getByRole('button', { name: `Weitere Aktionen für „${chat.title}“` }))
    await user.click(screen.getByRole('menuitem', { name: `Exportieren als ${label}` }))

    expect(exportChat).toHaveBeenCalledWith(chat, format)
  })
})
