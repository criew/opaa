import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import useMediaQuery from '@mui/material/useMediaQuery'
import visuallyHidden from '@mui/utils/visuallyHidden'
import PushPinOutlinedIcon from '@mui/icons-material/PushPinOutlined'
import type { ChatSummary } from '../../types/api'
import { adminTableSx, listCardSx } from '../admin/list/adminListStyles'
import ChatRowMenu from './ChatRowMenu'
import type { ChatRowActions } from './ChatRowMenu'
import { chatTitle } from './chatListSections'
import { deletionDueLabel } from './deletionDueLabel'

const dateTimeFormat = new Intl.DateTimeFormat('de-DE', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTimeFormat.format(new Date(iso)) : '–'
}

interface ChatTableProps extends ChatRowActions {
  spaceId: string
  rows: ChatSummary[]
  archived: boolean
  selection: ReadonlySet<string>
  onToggle: (chatId: string) => void
  onOpen: (chatId: string) => void
  busy: boolean
}

/** The selection column, aligned under "Alle auf dieser Seite auswählen". A plain cell rather
 *  than padding="checkbox": the theme narrows that one to the size of a bare box. */
const SELECT_CELL_SX = { width: 52, pl: 1.5, pr: 0 } as const

/** The title as the way into the chat, plus the pin mark as its second line. */
function TitleCell({
  chat,
  spaceId,
  onOpen,
}: {
  chat: ChatSummary
  spaceId: string
  onOpen: (chatId: string) => void
}) {
  const title = chatTitle(chat)
  return (
    <Box sx={{ minWidth: 0 }}>
      <Link
        href={`/spaces/${spaceId}/chats/${chat.id}`}
        onClick={(event) => {
          event.preventDefault()
          onOpen(chat.id)
        }}
        underline="hover"
        title={title}
        sx={{
          display: 'block',
          fontSize: 13.5,
          fontWeight: 500,
          color: 'text.primary',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {title}
      </Link>
      {chat.pinnedAt && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', mt: 0.25 }}>
          <PushPinOutlinedIcon aria-hidden sx={{ fontSize: 13, color: 'text.secondary' }} />
          <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
            angeheftet
          </Typography>
        </Stack>
      )}
    </Box>
  )
}

function SelectBox({
  chat,
  selection,
  onToggle,
}: Pick<ChatTableProps, 'selection' | 'onToggle'> & { chat: ChatSummary }) {
  return (
    <Checkbox
      size="small"
      checked={selection.has(chat.id)}
      onChange={() => onToggle(chat.id)}
      slotProps={{ input: { 'aria-label': `„${chatTitle(chat)}“ auswählen` } }}
      sx={{ p: 0.5 }}
    />
  )
}

/**
 * The chats of one tab, built from the same parts as the administration lists (Benutzer,
 * Gruppen): a fixed-layout table at desktop width, a plain list below it (guidelines 5.3). Each
 * row carries a selection box for the bulk actions and a row menu for the single ones.
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
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  if (!isDesktop) {
    return (
      <Stack spacing={0}>
        {rows.map((chat) => (
          <Box component="article" key={chat.id} aria-label={chatTitle(chat)} sx={listCardSx}>
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
              <Box sx={{ pt: 0.25 }}>
                <SelectBox chat={chat} selection={selection} onToggle={onToggle} />
              </Box>
              <Box sx={{ minWidth: 0, flexGrow: 1 }}>
                <TitleCell chat={chat} spaceId={spaceId} onOpen={onOpen} />
                <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
                  {archived && `Archiviert ${formatDateTime(chat.archivedAt)} · `}
                  Zuletzt {formatDateTime(chat.updatedAt)}
                </Typography>
                {archived && chat.deletionDueAt && (
                  <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                    {deletionDueLabel(chat.deletionDueAt)}
                  </Typography>
                )}
              </Box>
              <ChatRowMenu
                chat={chat}
                archived={archived}
                disabled={busy}
                onAction={onAction}
                onPin={onPin}
              />
            </Stack>
          </Box>
        ))}
      </Stack>
    )
  }

  return (
    <Table size="small" aria-label={archived ? 'Chat-Archiv' : 'Aktive Chats'} sx={adminTableSx}>
      <TableHead>
        <TableRow>
          <TableCell sx={SELECT_CELL_SX}>
            <span style={visuallyHidden}>Auswahl</span>
          </TableCell>
          <TableCell>Titel</TableCell>
          {archived && <TableCell sx={{ width: '20%' }}>Archiviert am</TableCell>}
          <TableCell sx={{ width: '20%' }}>Letzte Aktivität</TableCell>
          <TableCell align="right" sx={{ width: 56 }}>
            <span style={visuallyHidden}>Aktionen</span>
          </TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((chat) => (
          <TableRow key={chat.id} selected={selection.has(chat.id)}>
            <TableCell sx={{ ...SELECT_CELL_SX, py: 0.75 }}>
              <SelectBox chat={chat} selection={selection} onToggle={onToggle} />
            </TableCell>
            <TableCell>
              <TitleCell chat={chat} spaceId={spaceId} onOpen={onOpen} />
            </TableCell>
            {archived && (
              <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {formatDateTime(chat.archivedAt)}
                {chat.deletionDueAt && (
                  <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                    {deletionDueLabel(chat.deletionDueAt)}
                  </Typography>
                )}
              </TableCell>
            )}
            <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>
              {formatDateTime(chat.updatedAt)}
            </TableCell>
            <TableCell align="right" sx={{ py: 0.5 }}>
              <ChatRowMenu
                chat={chat}
                archived={archived}
                disabled={busy}
                onAction={onAction}
                onPin={onPin}
              />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
