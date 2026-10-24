import { forwardRef } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import type { SourceIssue } from '../../utils/sourceIssue'
import { fieldId } from './setupContext'

interface ErrorSummaryProps {
  issues: SourceIssue[]
  idPrefix: string
}

/**
 * All issues of a submit at once, above the form: one line per issue, a jump link to its field
 * when it names one. The same text stands at the field. Takes the focus after a failed submit.
 */
const ErrorSummary = forwardRef<HTMLDivElement, ErrorSummaryProps>(function ErrorSummary(
  { issues, idPrefix },
  ref,
) {
  if (issues.length === 0) return null
  const jump = (field: string) => {
    const target = document.getElementById(fieldId(idPrefix, field))
    target?.scrollIntoView({ block: 'center' })
    target?.focus()
  }
  return (
    <Box
      ref={ref}
      tabIndex={-1}
      role="alert"
      aria-labelledby={`${idPrefix}-error-summary-title`}
      sx={{
        borderLeft: 3,
        borderColor: 'error.main',
        pl: 2,
        py: 0.5,
        mb: 3,
        outline: 'none',
        '&:focus-visible': { boxShadow: '0 0 0 3px rgba(18,144,239,0.32)' },
      }}
    >
      <Typography
        id={`${idPrefix}-error-summary-title`}
        component="h3"
        sx={{ fontSize: 14, fontWeight: 600, mb: 0.5 }}
      >
        {issues.length === 1
          ? '1 Angabe fehlt oder ist ungültig'
          : `${issues.length} Angaben fehlen oder sind ungültig`}
      </Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5, fontSize: 13.5 }}>
        {issues.map((issue, index) => (
          <li key={`${issue.field ?? 'form'}-${index}`}>
            {issue.field ? (
              <Link
                component="button"
                type="button"
                onClick={() => jump(issue.field!)}
                sx={{
                  color: 'error.main',
                  textAlign: 'left',
                  fontSize: 13.5,
                  verticalAlign: 'baseline',
                }}
              >
                {issue.message}
              </Link>
            ) : (
              <Box component="span" sx={{ color: 'error.main' }}>
                {issue.message}
              </Box>
            )}
          </li>
        ))}
      </Box>
    </Box>
  )
})

export default ErrorSummary
