import { createContext, useContext, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import type { SourceIssue } from '../../utils/sourceIssue'

/** Marks the message of an issue in a helper text, so the field it describes can be found. */
export const ISSUE_MARKER = 'data-source-issue'

/** Hands the issues of a failed save to the source form, which shows each at its field. */
export const SourceIssueContext = createContext<SourceIssue | readonly SourceIssue[] | null>(null)

/** The issues of the context as a list, whether one or several were handed down. */
export function issueList(value: SourceIssue | readonly SourceIssue[] | null): SourceIssue[] {
  if (!value) return []
  return Array.isArray(value) ? [...value] : [value as SourceIssue]
}

/** Looks up the message of the current issues for one form value, null for any other value. */
export function useSourceIssue(): (field: string) => string | null {
  const issues = issueList(useContext(SourceIssueContext))
  return (field) => issues.find((issue) => issue.field === field)?.message ?? null
}

/**
 * The helper text of a field with an issue: the message first, the field's own hint kept below.
 * As helper text, MUI ties it to the field via `aria-describedby`.
 */
export function withIssue(message: string | null, hint?: ReactNode): ReactNode {
  if (!message) return hint
  return (
    <>
      <Box component="span" sx={{ display: 'block' }} {...{ [ISSUE_MARKER]: '' }}>
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
