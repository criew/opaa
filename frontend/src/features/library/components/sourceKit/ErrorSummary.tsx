import { forwardRef } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'

export interface SummaryIssue {
  message: string
  /** The DOM id of the control to jump to; absent for an issue of the whole step. */
  targetId?: string
}

/**
 * All issues of one submit at once (Prüfliste #29): above the form, each a link to its field.
 * The same message stands at the field itself; this list only gathers them. No icon, no tint -
 * a 1-px border in the danger role.
 */
const ErrorSummary = forwardRef<HTMLDivElement, { issues: SummaryIssue[] }>(function ErrorSummary(
  { issues },
  ref,
) {
  if (issues.length === 0) return null
  return (
    <Box
      ref={ref}
      tabIndex={-1}
      role="alert"
      aria-labelledby="error-summary-title"
      sx={{
        border: 1,
        borderColor: 'error.main',
        borderRadius: '10px',
        p: 2,
        mb: 3,
        outline: 'none',
        '&:focus-visible': { boxShadow: '0 0 0 3px rgba(18,144,239,0.32)' },
      }}
    >
      <Typography id="error-summary-title" component="h3" sx={{ fontSize: 15, fontWeight: 600 }}>
        {issues.length === 1 ? '1 Angabe prüfen' : `${issues.length} Angaben prüfen`}
      </Typography>
      <Box component="ul" sx={{ m: 0, mt: 1, pl: 2.5 }}>
        {issues.map((issue) => (
          <Box
            component="li"
            key={`${issue.targetId ?? ''}${issue.message}`}
            sx={{ fontSize: 14, py: 0.25 }}
          >
            {issue.targetId ? (
              <Link
                href={`#${issue.targetId}`}
                color="error"
                onClick={(event) => {
                  const target = document.getElementById(issue.targetId!)
                  if (!target) return
                  event.preventDefault()
                  target.scrollIntoView({ block: 'center' })
                  target.focus()
                }}
              >
                {issue.message}
              </Link>
            ) : (
              issue.message
            )}
          </Box>
        ))}
      </Box>
    </Box>
  )
})

export default ErrorSummary
