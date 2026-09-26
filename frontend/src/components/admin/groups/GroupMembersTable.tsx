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

/** From this many members on, a filter field helps more than it clutters. */
const FILTER_THRESHOLD = 8

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
 * Die Mitglieder einer Gruppe als Tabelle (#1978): alphabetisch, mit fester Kopfzeile in einem
 * begrenzt hohen, scrollbaren Bereich, ab einigen Einträgen mit einem Filterfeld. Die Liste liegt
 * vollständig vor; gefiltert wird im Browser.
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
      {members.length >= FILTER_THRESHOLD && (
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
        sx={{ maxHeight: 360, border: 1, borderColor: 'divider', borderRadius: 1 }}
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
      {/* The total stands in the dialog title; only a filtered view needs its own count. */}
      {needle && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.75 }}>
          {`${shown.length} von ${members.length} Mitgliedern`}
        </Typography>
      )}
    </>
  )
}
