import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LibraryResponse } from '@/shared/api/types'
import { sourceRegistration } from './sources/registry'
import type { SourceFormContext, SourceScopeHero } from './sources/types'

/** The chosen names of the library's scope, as the row „Umfang“ names them; null without a setup. */
function scopeNames(details: LibraryResponse): string | null {
  const configuration = sourceRegistration(details.sourceType)?.configuration
  const scope = configuration?.setup?.sections.find((section) => section.id === 'scope')
  if (!configuration || !scope) return null
  // the scope summaries read the values only
  const context = { mode: 'edit', sourceType: details.sourceType } as SourceFormContext
  return scope.summary(configuration.fromLibrary(details), context, 'unchecked')
}

interface LibraryScopeHeroProps {
  details: LibraryResponse
  scopeHero: SourceScopeHero
  /** Scope entries the last run could not read. */
  unlistedScopeKeys: string[]
  /** The search of the page's „Quelle" tab. */
  sourceSearch: string
}

/** The scope of a connector library as one line in the head, with the warning about unread parts. */
export default function LibraryScopeHero({
  details,
  scopeHero,
  unlistedScopeKeys,
  sourceSearch,
}: LibraryScopeHeroProps) {
  return (
    <Box
      sx={{
        mt: 2.5,
        pt: 2,
        borderTop: 1,
        borderTopColor: 'divider',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
      }}
    >
      <Typography
        variant="body2"
        data-testid="library-scope-summary"
        sx={{
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {scopeNames(details) ?? scopeHero.summary(details)}
        {' · '}
        <Link component={RouterLink} to={{ search: sourceSearch }} replace underline="hover">
          Details
        </Link>
      </Typography>
      {unlistedScopeKeys.length > 0 && (
        <Stack
          direction="row"
          spacing={1}
          role="note"
          data-testid={scopeHero.unlistedTestId}
          sx={{
            alignItems: 'flex-start',
            mt: 0.5,
            pl: 1.5,
            pr: 1.25,
            py: 1,
            // one signal: the edge on the left, no tint, no icon (rule 4)
            borderLeft: 3,
            borderColor: 'warning.main',
          }}
        >
          <Typography sx={{ fontSize: 12.5 }}>
            {scopeHero.unlistedWarning(unlistedScopeKeys, details)}
          </Typography>
        </Stack>
      )}
    </Box>
  )
}
