import type { ReactNode } from 'react'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import { fontSize, iconSize } from '@/shared/theme/tokens'

/** The note under a scope: everything indexed from it is visible to every reader of the library. */
export default function ScopeConsequence({
  testId,
  children,
}: {
  testId: string
  children: ReactNode
}) {
  return (
    <Stack
      direction="row"
      spacing={1}
      role="note"
      data-testid={testId}
      sx={{ alignItems: 'flex-start', mt: 0.5 }}
    >
      <InfoOutlinedIcon
        aria-hidden
        sx={{ fontSize: iconSize.sm, color: 'primary.main', mt: '2px', flexShrink: 0 }}
      />
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>{children}</Typography>
    </Stack>
  )
}
