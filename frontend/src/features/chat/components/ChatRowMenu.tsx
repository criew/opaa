import type { ChatBulkAction, ChatSummary } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { chatTitle } from './chatListSections'
import { exportChat } from './exportChat'

export interface ChatRowActions {
  /** Archives, brings back or deletes the given chats - the same path as the bulk actions. */
  onAction: (action: ChatBulkAction, chats: ChatSummary[]) => void
  onPin: (chat: ChatSummary, pinned: boolean) => void
}

interface ChatRowMenuProps extends ChatRowActions {
  chat: ChatSummary
  archived: boolean
  disabled?: boolean
}

/**
 * The row menu of one chat on the chats page, built like the row menus of the administration
 * lists: the everyday actions first, then the two exports, "Löschen" last under a divider. An
 * archived chat is brought back rather than pinned or archived; it can be exported all the same.
 */
export default function ChatRowMenu({
  chat,
  archived,
  disabled,
  onAction,
  onPin,
}: ChatRowMenuProps) {
  return (
    <RowMenu
      name={chatTitle(chat)}
      disabled={disabled}
      items={[
        archived && {
          label: 'Zurückholen',
          onClick: () => onAction('UNARCHIVE', [chat]),
        },
        !archived && {
          label: chat.pinnedAt ? 'Lösen' : 'Anheften',
          onClick: () => onPin(chat, !chat.pinnedAt),
        },
        !archived && {
          label: 'Archivieren',
          onClick: () => onAction('ARCHIVE', [chat]),
        },
        {
          label: 'Exportieren als Markdown',
          onClick: () => void exportChat(chat, 'markdown'),
        },
        {
          label: 'Exportieren als PDF',
          onClick: () => void exportChat(chat, 'pdf'),
        },
        {
          label: 'Löschen',
          onClick: () => onAction('DELETE', [chat]),
          danger: true,
        },
      ]}
    />
  )
}
