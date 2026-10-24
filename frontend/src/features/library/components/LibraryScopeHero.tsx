import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { LibraryResponse } from '@/shared/api/types'
import type { SourceScopeHero } from './sources/types'

interface LibraryScopeHeroProps {
  details: LibraryResponse
  scopeHero: SourceScopeHero
  /** Scope entries the last run could not read. */
  unlistedScopeKeys: string[]
  /** The search of the page's „Quelle" tab; the scope itself is named there, not in the head. */
  sourceSearch: string
}

/** The warning about parts of the scope the last run could not read; nothing while all were read. */
export default function LibraryScopeHero({
  details,
  scopeHero,
  unlistedScopeKeys,
}: LibraryScopeHeroProps) {
  if (unlistedScopeKeys.length === 0) return null
  return (
    <Box
      role="note"
      data-testid={scopeHero.unlistedTestId}
      // one signal: the edge on the left, no tint, no icon (rule 4)
      sx={{ mt: 2.5, pl: 1.5, py: 0.5, borderLeft: 3, borderColor: 'warning.main' }}
    >
      <Typography sx={{ fontSize: 12.5 }}>
        {scopeHero.unlistedWarning(unlistedScopeKeys, details)}
      </Typography>
    </Box>
  )
}
