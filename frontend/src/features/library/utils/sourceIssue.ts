/**
 * Why the values of a source form may not be sent. `field` names the form value the message is
 * about (`sourceUrl`, `scopes.0.bucket`), so the form can show it at that field; without one the
 * message concerns the form as a whole.
 */
export interface SourceIssue {
  message: string
  field?: string
}

export function issueAt(field: string, message: string): SourceIssue {
  return { message, field }
}

export function formIssue(message: string): SourceIssue {
  return { message }
}

export function messageOf(issue: SourceIssue | null): string | null {
  return issue?.message ?? null
}
