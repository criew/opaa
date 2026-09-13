import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import PageHeading from '../a11y/PageHeading'

interface AuthNoticeProps {
  /** The page's one h1 - it stays the same whether the outcome was success or failure. */
  heading: string
  severity: 'success' | 'error' | 'warning' | 'info'
  message: string
  /** Further sentence below the alert, e.g. what happens next. */
  detail?: string
  /** The way on from here - always exactly one, never a dead end. */
  children?: ReactNode
}

/**
 * The outcome of a self-service step as a view of its own rather than a popup (#1540): the form is
 * gone, the sentence stands where the form stood, and there is one way on. A popup would vanish
 * after a few seconds from a screen that has no other content left. MUI's Alert carries
 * `role="alert"`, so the sentence is announced when it replaces the form.
 */
export default function AuthNotice({
  heading,
  severity,
  message,
  detail,
  children,
}: AuthNoticeProps) {
  return (
    <>
      <PageHeading title={heading} variant="h5" />
      <Alert severity={severity} sx={{ mt: 2, textAlign: 'left' }}>
        {message}
      </Alert>
      {detail && (
        <Typography sx={{ mt: 2, fontSize: 13.5, color: 'text.secondary' }}>{detail}</Typography>
      )}
      {children && <Box sx={{ mt: 3, fontSize: 13 }}>{children}</Box>}
    </>
  )
}
