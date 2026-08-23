import type { ReactNode } from 'react'
import Typography from '@mui/material/Typography'
import type { SxProps, Theme } from '@mui/material/styles'

interface GlobalScopeNoteProps {
  /** Defaults to the administration sentence (mockup 2b); mockup 2c pages pass their own. */
  children?: ReactNode
  sx?: SxProps<Theme>
}

/**
 * The scope sentence global pages carry under their heading (#787/#788, mockups 2b/2c) -
 * one component so wording and styling cannot drift between pages.
 */
export default function GlobalScopeNote({ children, sx }: GlobalScopeNoteProps) {
  return (
    <Typography
      sx={[
        { fontSize: 12.5, color: 'text.secondary', mt: -1.5, mb: 2.5 },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children ??
        'Gilt für die gesamte Anwendung. Änderungen wirken sich auf alle Spaces und Benutzer aus.'}
    </Typography>
  )
}
