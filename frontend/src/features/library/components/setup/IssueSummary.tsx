import { forwardRef } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import type { SourceIssue } from '../../utils/sourceIssue'
import { focusIssue } from './focusIssue'

interface IssueSummaryProps {
  issues: SourceIssue[]
  /** The element whose fields the links jump to. */
  scope: () => HTMLElement | null
}

/**
 * Every issue of a failed „Weiter" or „Speichern" at once, above the form, each a link to its field
 * (GOV.UK error summary). The messages are the same as at the fields; the colour sits in the edge,
 * the text stays in the text colour.
 */
const IssueSummary = forwardRef<HTMLDivElement, IssueSummaryProps>(function IssueSummary(
  { issues, scope },
  ref,
) {
  if (issues.length === 0) return null
  return (
    <Box
      ref={ref}
      tabIndex={-1}
      role="alert"
      aria-labelledby="issue-summary-title"
      sx={{
        borderLeft: 3,
        borderColor: 'error.main',
        pl: 2,
        py: 0.5,
        mb: 3,
        outline: 'none',
      }}
    >
      <Typography id="issue-summary-title" component="h3" sx={{ fontSize: 15, fontWeight: 600 }}>
        {issues.length === 1
          ? 'Eine Angabe fehlt oder stimmt nicht'
          : `${issues.length} Angaben fehlen oder stimmen nicht`}
      </Typography>
      <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
        {issues.map((issue) => (
          <Box component="li" key={`${issue.field ?? ''}${issue.message}`} sx={{ fontSize: 13.5 }}>
            <Link
              component="button"
              type="button"
              onClick={() => focusIssue(issue, scope())}
              sx={{ fontSize: 13.5, textAlign: 'left', minHeight: 24, py: 0.25 }}
            >
              {issue.message}
            </Link>
          </Box>
        ))}
      </Box>
    </Box>
  )
})

export default IssueSummary
