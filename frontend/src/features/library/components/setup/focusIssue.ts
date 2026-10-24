import type { SourceIssue } from '../../utils/sourceIssue'

/** Focuses the field of `issue` inside `scope`, or the group it belongs to. */
export function focusIssue(issue: SourceIssue, scope: HTMLElement | null) {
  if (!scope) return
  const target =
    (issue.field && scope.querySelector<HTMLElement>(`[data-source-field="${issue.field}"]`)) ||
    scope.querySelector<HTMLElement>('[data-source-field="selection"]') ||
    scope.querySelector<HTMLElement>('[data-source-field="connection"]')
  if (!target) return
  target.scrollIntoView({ block: 'center' })
  target.focus()
}
