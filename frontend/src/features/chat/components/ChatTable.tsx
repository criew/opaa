import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PushPinOutlinedIcon from '@mui/icons-material/PushPinOutlined'
import type { ChatSummary } from '@/shared/api/types'
import AdminList, { ListText, type AdminListColumn } from '@/shared/ui/list/AdminList'
import ChatRowMenu from './ChatRowMenu'
import type { ChatRowActions } from './ChatRowMenu'
import { chatTitle } from './chatListSections'
import { deletionDueLabel } from './deletionDueLabel'
import { fontSize, iconSize } from '@/shared/theme/tokens'
import { formatDateTime } from '@/shared/utils/formatDate'

interface ChatTableProps extends ChatRowActions {
  spaceId: string
  rows: ChatSummary[]
  archived: boolean
  selection: ReadonlySet<string>
  onToggle: (chatId: string) => void
  onOpen: (chatId: string) => void
  busy: boolean
}

/** The title, plus the pin mark as its second line. */
function TitleCell({ chat }: { chat: ChatSummary }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <ListText strong>{chatTitle(chat)}</ListText>
      {chat.pinnedAt && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', mt: 0.25 }}>
          <PushPinOutlinedIcon
            aria-hidden
            sx={{ fontSize: iconSize.xs, color: 'text.secondary' }}
          />
          <Typography component="span" sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
            angeheftet
          </Typography>
        </Stack>
      )}
    </Box>
  )
}

/**
 * The chats of one tab, built from the same building block as the administration lists: a table
 * at desktop width, a card list below it (guidelines 5.3). The whole row leads into the chat;
 * each row carries a selection box for the bulk actions and a row menu for the single ones.
 */
export default function ChatTable({
  spaceId,
  rows,
  archived,
  selection,
  onToggle,
  onOpen,
  busy,
  onAction,
  onPin,
}: ChatTableProps) {
  const columns: AdminListColumn<ChatSummary>[] = [
    { id: 'title', head: 'Titel', cell: (chat) => <TitleCell chat={chat} /> },
    ...(archived
      ? [
          {
            id: 'archivedAt',
            head: 'Archiviert am',
            width: '20%',
            cell: (chat: ChatSummary) => (
              <>
                <ListText>{formatDateTime(chat.archivedAt)}</ListText>
                {chat.deletionDueAt && (
                  <ListText secondary>{deletionDueLabel(chat.deletionDueAt)}</ListText>
                )}
              </>
            ),
          },
        ]
      : []),
    {
      id: 'updatedAt',
      head: 'Letzte Aktivität',
      width: '20%',
      cell: (chat) => formatDateTime(chat.updatedAt),
    },
  ]

  return (
    <AdminList
      label={archived ? 'Chat-Archiv' : 'Aktive Chats'}
      rows={rows}
      rowKey={(chat) => chat.id}
      rowLabel={chatTitle}
      columns={columns}
      rowTarget={(chat) => ({
        href: `/spaces/${spaceId}/chats/${chat.id}`,
        onOpen: () => onOpen(chat.id),
      })}
      isSelected={(chat) => selection.has(chat.id)}
      leading={{
        head: 'Auswahl',
        width: 52,
        cell: (chat) => (
          <Checkbox
            size="small"
            checked={selection.has(chat.id)}
            onChange={() => onToggle(chat.id)}
            slotProps={{ input: { 'aria-label': `„${chatTitle(chat)}“ auswählen` } }}
            sx={{ p: 0.5 }}
          />
        ),
      }}
      rowActions={(chat) => (
        <ChatRowMenu
          chat={chat}
          archived={archived}
          disabled={busy}
          onAction={onAction}
          onPin={onPin}
        />
      )}
    />
  )
}
