import { useMemo, useState } from 'react'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { GroupMemberResponse } from '@/shared/api/types'
import AdminList, { ListText, RowAction } from '@/shared/ui/list/AdminList'
import { fontSize } from '@/shared/theme/tokens'
import { formatDate } from '@/shared/utils/formatDate'

/**
 * Fixed row geometry, so the frame height follows from the member count alone. The values hold
 * for the default font size; with a larger font the frame scrolls, which is intended.
 */
const HEAD_HEIGHT = 37
const ROW_HEIGHT = 41
/** A row with the „Entfernen“ button: the button and the cell padding make it taller. */
const REMOVABLE_ROW_HEIGHT = 52
const VISIBLE_ROWS = 8
const FRAME_BORDER = 2

/**
 * The height of the scroll frame: as many rows as the group has, at most {@link VISIBLE_ROWS}.
 * It depends on the whole list, never on the filtered one - filtering must not resize the dialog.
 */
function frameHeight(memberCount: number, rowHeight: number): number {
  const rows = Math.min(Math.max(memberCount, 1), VISIBLE_ROWS)
  return HEAD_HEIGHT + rows * rowHeight + FRAME_BORDER
}

const collator = new Intl.Collator('de-DE', { sensitivity: 'base' })

function nameOf(member: GroupMemberResponse): string {
  return member.displayName ?? member.userId
}

function formatSince(value: string): string {
  return formatDate(value)
}

interface GroupMembersTableProps {
  members: GroupMemberResponse[]
  /** Offers „Entfernen“ per row; absent for a group whose source maintains its members. */
  onRemove?: (member: GroupMemberResponse) => void
}

/**
 * The members of a group as a table: alphabetical, with a fixed header row in a
 * scrollable frame, with a filter field for every group with members - internal and external
 * alike. The list is complete; filtering happens in the browser. Frame and result row keep
 * their height while filtering, so that the dialog does not jump.
 */
export default function GroupMembersTable({ members, onRemove }: GroupMembersTableProps) {
  const [filter, setFilter] = useState('')
  const rowHeight = onRemove ? REMOVABLE_ROW_HEIGHT : ROW_HEIGHT
  const sorted = useMemo(
    () => [...members].sort((a, b) => collator.compare(nameOf(a), nameOf(b))),
    [members],
  )
  const needle = filter.trim().toLocaleLowerCase('de-DE')
  const shown = needle
    ? sorted.filter((member) => nameOf(member).toLocaleLowerCase('de-DE').includes(needle))
    : sorted

  return (
    <>
      {members.length > 0 && (
        <TextField
          size="small"
          fullWidth
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Mitglieder filtern …"
          slotProps={{ htmlInput: { 'aria-label': 'Mitglieder filtern' } }}
          sx={{ mb: 1.5 }}
        />
      )}
      <AdminList
        label="Mitglieder"
        rows={shown}
        rowKey={(member) => member.userId}
        rowLabel={nameOf}
        // Below this width the frame scrolls sideways instead of squeezing the name column.
        minWidth={onRemove ? 400 : 344}
        frame={{
          height: frameHeight(members.length, rowHeight),
          headHeight: HEAD_HEIGHT,
          rowHeight,
          emptyText:
            members.length === 0
              ? 'Diese Gruppe hat keine Mitglieder.'
              : 'Kein Mitglied entspricht dem Filter.',
        }}
        columns={[
          {
            id: 'name',
            head: 'Name',
            cell: (member) => <ListText mono={!member.displayName}>{nameOf(member)}</ListText>,
          },
          {
            id: 'since',
            head: 'Mitglied seit',
            width: '32%',
            cell: (member) => <ListText secondary>{formatSince(member.createdAt)}</ListText>,
          },
        ]}
        actionsWidth={120}
        rowActions={
          onRemove
            ? (member) => (
                <RowAction label={`${nameOf(member)} entfernen`} onClick={() => onRemove(member)}>
                  Entfernen
                </RowAction>
              )
            : undefined
        }
      />
      {/* The total stands in the dialog title; only a filtered view needs its own count. The line
          keeps its place while empty, so typing the first letter does not push the dialog. */}
      {members.length > 0 && (
        <Typography
          aria-live="polite"
          sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 0.75, minHeight: '1.5em' }}
        >
          {needle ? `${shown.length} von ${members.length} Mitgliedern` : ''}
        </Typography>
      )}
    </>
  )
}
