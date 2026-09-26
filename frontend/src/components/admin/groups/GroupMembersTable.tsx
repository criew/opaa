import { useMemo, useState } from 'react'
import Button from '@mui/material/Button'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { visuallyHidden } from '@mui/utils'
import type { GroupMemberResponse } from '../../../types/api'
import { adminTableSx } from '../list/adminListStyles'

/** Fixed row geometry, so the frame height follows from the member count alone. */
const HEAD_HEIGHT = 37
const ROW_HEIGHT = 41
const VISIBLE_ROWS = 8
const FRAME_BORDER = 2

/**
 * The height of the scroll frame: as many rows as the group has, at most {@link VISIBLE_ROWS}.
 * It depends on the whole list, never on the filtered one - filtering must not resize the dialog.
 */
function frameHeight(memberCount: number): number {
  const rows = Math.min(Math.max(memberCount, 1), VISIBLE_ROWS)
  return HEAD_HEIGHT + rows * ROW_HEIGHT + FRAME_BORDER
}

const collator = new Intl.Collator('de-DE', { sensitivity: 'base' })

function nameOf(member: GroupMemberResponse): string {
  return member.displayName ?? member.userId
}

function formatSince(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', { dateStyle: 'medium' })
}

interface GroupMembersTableProps {
  members: GroupMemberResponse[]
  /** Offers „Entfernen“ per row; absent for a group whose source maintains its members. */
  onRemove?: (member: GroupMemberResponse) => void
}

/**
 * Die Mitglieder einer Gruppe als Tabelle: alphabetisch, mit fester Kopfzeile in einem
 * scrollbaren Rahmen, mit einem Filterfeld für jede Gruppe mit Mitgliedern - interne wie externe. Die Liste liegt vollständig vor;
 * gefiltert wird im Browser. Rahmen und Ergebniszeile behalten beim Filtern ihre Höhe, damit der
 * Dialog nicht springt.
 */
export default function GroupMembersTable({ members, onRemove }: GroupMembersTableProps) {
  const [filter, setFilter] = useState('')
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
      <TableContainer
        sx={{
          height: frameHeight(members.length),
          // Rules above and below mark the scroll region; a full frame is reserved for empty
          // states in this design system.
          borderTop: 1,
          borderBottom: 1,
          borderColor: 'divider',
          '& thead tr': { height: HEAD_HEIGHT },
          '& tbody tr': { height: ROW_HEIGHT },
        }}
        role="region"
        tabIndex={0}
        aria-label="Mitglieder, scrollbar"
      >
        <Table size="small" stickyHeader aria-label="Mitglieder" sx={adminTableSx}>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell sx={{ width: '32%' }}>Mitglied seit</TableCell>
              {onRemove && (
                <TableCell align="right" sx={{ width: 110 }}>
                  <span style={visuallyHidden}>Aktionen</span>
                </TableCell>
              )}
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((member) => (
              <TableRow key={member.userId} hover>
                <TableCell
                  sx={{
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    ...(member.displayName ? {} : { fontFamily: 'monospace', fontSize: 12 }),
                  }}
                  title={nameOf(member)}
                >
                  {nameOf(member)}
                </TableCell>
                <TableCell sx={{ color: 'text.secondary' }}>
                  {formatSince(member.createdAt)}
                </TableCell>
                {onRemove && (
                  <TableCell align="right" sx={{ py: 0.5 }}>
                    <Button
                      color="error"
                      size="small"
                      onClick={() => onRemove(member)}
                      aria-label={`${nameOf(member)} entfernen`}
                    >
                      Entfernen
                    </Button>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {shown.length === 0 && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary', px: 2, py: 1.5 }}>
            {members.length === 0
              ? 'Diese Gruppe hat keine Mitglieder.'
              : 'Kein Mitglied entspricht dem Filter.'}
          </Typography>
        )}
      </TableContainer>
      {/* The total stands in the dialog title; only a filtered view needs its own count. The line
          keeps its place while empty, so typing the first letter does not push the dialog. */}
      {members.length > 0 && (
        <Typography
          aria-live="polite"
          sx={{ fontSize: 12, color: 'text.secondary', mt: 0.75, minHeight: '1.5em' }}
        >
          {needle ? `${shown.length} von ${members.length} Mitgliedern` : ''}
        </Typography>
      )}
    </>
  )
}
