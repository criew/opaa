import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { alpha } from '@mui/material/styles'
import type { LibraryResponse } from '@/shared/api/types'
import type { SourceScopeHero } from './sources/types'

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
      <Typography variant="body2" data-testid="library-scope-summary">
        {scopeHero.summary(details)}
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
            // Highlighting as a signal edge on the left instead of a frame all around
            // (rule 4): the same means as in the confirmation overlay, so that a hint
            // looks the same everywhere. The signal colour stays in edge and icon — never
            // in running text.
            borderLeft: 3,
            borderColor: 'warning.main',
            bgcolor: (theme) => alpha(theme.palette.warning.main, 0.08),
          }}
        >
          <WarningAmberIcon
            aria-hidden
            sx={{ fontSize: 16, color: 'warning.main', mt: '2px', flexShrink: 0 }}
          />
          <Typography sx={{ fontSize: 12.5 }}>
            {scopeHero.unlistedWarning(unlistedScopeKeys, details)}
          </Typography>
        </Stack>
      )}
    </Box>
  )
}
