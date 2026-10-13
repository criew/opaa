import { createContext, useContext, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import type { SourceIssue } from '../../../utils/sourceIssue'

/** Hands the issue of a failed save to the source form, which shows it at its field. */
export const SourceIssueContext = createContext<SourceIssue | null>(null)

/** Looks up the message of the current issue for one form value, null for any other value. */
export function useSourceIssue(): (field: string) => string | null {
  const issue = useContext(SourceIssueContext)
  return (field) => (issue?.field === field ? issue.message : null)
}

/**
 * The helper text of a field with an issue: the message first, the field's own hint kept below.
 * As helper text, MUI ties it to the field via `aria-describedby`.
 */
export function withIssue(message: string | null, hint?: ReactNode): ReactNode {
  if (!message) return hint
  return (
    <>
      <Box component="span" sx={{ display: 'block' }}>
        {message}
      </Box>
      {hint && (
        <Box component="span" sx={{ display: 'block', color: 'text.secondary' }}>
          {hint}
        </Box>
      )}
    </>
  )
}
