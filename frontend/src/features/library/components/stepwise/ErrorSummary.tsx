import { forwardRef } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'

export interface SummaryError {
  message: string
  /** The id of the field the link jumps to; none for an error of the whole page. */
  targetId?: string
  /** Instead of a field on this page: what the link does (the step „Prüfen“ leads to a step). */
  onSelect?: () => void
}

/**
 * The list of every problem of a page after „Weiter“ or „Speichern“ (GOV.UK error summary): one
 * link per field, the same sentence as under the field. It takes the focus when it appears.
 */
const ErrorSummary = forwardRef<HTMLDivElement, { errors: SummaryError[] }>(function ErrorSummary(
  { errors },
  ref,
) {
  if (errors.length === 0) return null
  return (
    <Box
      ref={ref}
      tabIndex={-1}
      role="alert"
      aria-labelledby="error-summary-title"
      sx={{
        border: 1,
        borderColor: 'error.main',
        borderLeftWidth: 3,
        borderRadius: 1,
        px: 2,
        py: 1.5,
        mb: 3,
        maxWidth: 560,
        '&:focus-visible': { outline: '3px solid', outlineColor: 'primary.main', outlineOffset: 2 },
      }}
    >
      <Typography
        id="error-summary-title"
        component="h3"
        sx={{ fontSize: 15, fontWeight: 600, mb: 0.5 }}
      >
        {errors.length === 1
          ? 'Eine Angabe fehlt oder stimmt nicht'
          : `${errors.length} Angaben fehlen oder stimmen nicht`}
      </Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
        {errors.map((error) => (
          <Box
            component="li"
            key={`${error.targetId ?? ''}${error.message}`}
            sx={{ fontSize: 14, py: 0.25 }}
          >
            {error.targetId || error.onSelect ? (
              <Link
                href={`#${error.targetId ?? ''}`}
                onClick={(event) => {
                  event.preventDefault()
                  if (error.onSelect) {
                    error.onSelect()
                    return
                  }
                  const target = document.getElementById(error.targetId!)
                  target?.focus()
                  target?.scrollIntoView({ block: 'center' })
                }}
                sx={{ color: 'error.main', textDecorationColor: 'currentColor' }}
              >
                {error.message}
              </Link>
            ) : (
              error.message
            )}
          </Box>
        ))}
      </Box>
    </Box>
  )
})

export default ErrorSummary
