import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { Link as RouterLink } from 'react-router'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import { stateTargetHref, type StateTarget } from './libraryState'
import type { NextStep } from './nextSteps'

const ROW_ANCHORS: Record<string, StateTarget['anchor']> = {
  connection: 'verbindung',
  live: 'live',
}

/**
 * The steps after creating a library, once, under the tabs: ✓ for what is done, ○ with a link to
 * its place for what is open. Below 600 px only the open steps; „Ausblenden“ ends the list.
 */
export default function LibraryNextSteps({
  steps,
  onDismiss,
}: {
  steps: NextStep[]
  onDismiss: () => void
}) {
  if (steps.length === 0) return null
  return (
    <DetailSection
      title="Nächste Schritte"
      action={<TextAction onClick={onDismiss}>Ausblenden</TextAction>}
    >
      <Box
        component="ul"
        data-testid="next-steps"
        sx={{ m: 0, p: 0, listStyle: 'none', borderTop: 1, borderColor: 'divider', pt: 1 }}
      >
        {steps.map((step) => (
          <Box
            component="li"
            key={step.key}
            sx={{
              display: { xs: step.done ? 'none' : 'flex', sm: 'flex' },
              gap: 1,
              fontSize: 13.5,
              lineHeight: '26px',
            }}
          >
            <Box
              component="span"
              aria-hidden
              sx={{ width: 14, flex: 'none', color: step.done ? 'success.main' : 'text.secondary' }}
            >
              {step.done ? '✓' : '○'}
            </Box>
            {step.done ? (
              <Typography component="span" sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                {step.label}
                <Box component="span" sx={visuallyHidden}>
                  {' (erledigt)'}
                </Box>
              </Typography>
            ) : (
              <Link
                component={RouterLink}
                to={stateTargetHref({
                  tab: step.tab,
                  anchor: step.row ? ROW_ANCHORS[step.row] : undefined,
                })}
                replace
                underline="hover"
                sx={{ fontSize: 13.5 }}
              >
                {step.label}
              </Link>
            )}
          </Box>
        ))}
      </Box>
    </DetailSection>
  )
}
