import type { ReactNode } from 'react'
import Box from '@mui/material/Box'

interface SubjectFormRowProps {
  /** The search field, usually {@link SubjectPicker}; it takes the remaining width. */
  picker: ReactNode
  /** The narrow role select. */
  role: ReactNode
  /** The button, at its natural width. */
  action: ReactNode
  /** Width of the role select from 600 px on. */
  roleWidth?: number
}

/**
 * The "recipient, role, add" row of the member and grant forms. Below 600 px the search stands
 * alone on top, role and button below it.
 */
export default function SubjectFormRow({
  picker,
  role,
  action,
  roleWidth = 170,
}: SubjectFormRowProps) {
  return (
    <Box
      sx={{
        display: 'grid',
        gap: 1.5,
        alignItems: 'center',
        gridTemplateColumns: {
          xs: 'minmax(0, 1fr) auto',
          sm: `minmax(0, 1fr) ${roleWidth}px auto`,
        },
      }}
    >
      <Box sx={{ gridColumn: { xs: '1 / -1', sm: 'auto' }, minWidth: 0 }}>{picker}</Box>
      <Box sx={{ minWidth: 0, '& .MuiInputBase-root': { width: '100%' } }}>{role}</Box>
      <Box>{action}</Box>
    </Box>
  )
}
