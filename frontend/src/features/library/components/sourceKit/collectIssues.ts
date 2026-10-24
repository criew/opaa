import type { SourceIssue } from '../../utils/sourceIssue'

/**
 * Every issue of a staged validator at once (Prüfliste #29). The connectors' validators stop at
 * the first issue; this runs one again with each reported field replaced by a valid stand-in, so
 * later fields are checked too. A field without a stand-in, or an issue without a field, ends
 * the walk - what follows depends on it. `rename` maps a validator's field to the form's.
 */
export function collectIssues<V extends object>(
  values: V,
  validate: (values: V) => SourceIssue | null,
  standIns: Partial<Record<string, unknown>>,
  rename: (field: string) => string = (field) => field,
): SourceIssue[] {
  const issues: SourceIssue[] = []
  let probe: V = values
  const seen = new Set<string>()
  for (let round = 0; round < 12; round++) {
    const issue = validate(probe)
    if (!issue) break
    const field = issue.field ? rename(issue.field) : undefined
    if (!issues.some((known) => known.field !== undefined && known.field === field)) {
      issues.push(field ? { message: issue.message, field } : { message: issue.message })
    }
    if (!issue.field || !(issue.field in standIns) || seen.has(issue.field)) break
    seen.add(issue.field)
    probe = { ...probe, [issue.field]: standIns[issue.field] }
  }
  return issues
}

/**
 * Issues of empty required fields in the one wording every connector shares (Prüfliste #19):
 * „Bitte <Feld> eingeben.“ - `labels` names each field in the accusative, with its article.
 */
export function unifyMissing(
  issues: SourceIssue[],
  values: object,
  labels: Record<string, string>,
): SourceIssue[] {
  const entered = values as Record<string, unknown>
  return issues.map((issue) => {
    const label = issue.field ? labels[issue.field] : undefined
    const value = issue.field ? entered[issue.field] : undefined
    return label && typeof value === 'string' && value.trim() === ''
      ? { ...issue, message: `Bitte ${label} eingeben.` }
      : issue
  })
}
