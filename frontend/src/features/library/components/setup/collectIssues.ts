import type { SourceIssue } from '../../utils/sourceIssue'

/**
 * Every issue of `values`, not only the first: the connector's own rule finds one issue at a time,
 * so each issue's field is set to a value that passes (`repairs`) and the rule runs again. An
 * issue without a field, or with a field that has no repair, ends the list.
 */
export function collectIssues<V extends object>(
  values: V,
  firstIssue: (values: V) => SourceIssue | null,
  repairs: Partial<Record<string, unknown>>,
): SourceIssue[] {
  const issues: SourceIssue[] = []
  let probe: V = values
  for (let round = 0; round < 12; round++) {
    const issue = firstIssue(probe)
    if (!issue) break
    issues.push(issue)
    const field = issue.field
    if (!field || !(field in repairs)) break
    if (issues.filter((known) => known.field === field).length > 1) break
    probe = { ...probe, [field]: repairs[field] }
  }
  return issues
}
