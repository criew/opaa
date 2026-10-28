import type { ReactNode } from 'react'
import Box from '@mui/material/Box'

interface SubjectFormRowProps {
  /** The search field, {@link SubjectPicker} or, where only persons qualify, `UserPicker`. */
  picker: ReactNode
  /** The narrow role select; left out where the addition knows no role. */
  role?: ReactNode
  /** The button, at its natural width. */
  action: ReactNode
  /** Width of the role select from 600 px on. */
  roleWidth?: number
}

/** Height of a field; role and button center on it, so that a message below the search shifts nothing. */
const FIELD_HEIGHT = 40

/**
 * The "recipient, role, add" row of every form that adds persons or groups. Below 600 px the
 * search stands alone on top, role and button below it.
 */
export default function SubjectFormRow({
  picker,
  role,
  action,
  roleWidth = 170,
}: SubjectFormRowProps) {
  const cell = { minHeight: FIELD_HEIGHT, display: 'flex', alignItems: 'center', minWidth: 0 }
  return (
    <Box
      sx={{
        display: 'grid',
        gap: 1.5,
        alignItems: 'start',
        gridTemplateColumns: role
          ? { xs: 'minmax(0, 1fr) auto', sm: `minmax(0, 1fr) ${roleWidth}px auto` }
          : { xs: 'minmax(0, 1fr)', sm: 'minmax(0, 1fr) auto' },
      }}
    >
      <Box sx={{ gridColumn: { xs: '1 / -1', sm: 'auto' }, minWidth: 0 }}>{picker}</Box>
      {role && <Box sx={{ ...cell, '& .MuiInputBase-root': { width: '100%' } }}>{role}</Box>}
      <Box sx={cell}>{action}</Box>
    </Box>
  )
}
