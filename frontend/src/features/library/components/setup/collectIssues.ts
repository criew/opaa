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

/**
 * The issues of an account with a password in the shared wording: each missing half at its own
 * field. With `kept`, a stored pair stands as long as neither half is typed.
 */
export function accountIssues(
  account: string,
  secret: string,
  kept: boolean,
  fields: { account: string; secret: string; secretLabel: string },
): SourceIssue[] {
  const issues: SourceIssue[] = []
  const typed = account.trim() !== '' || secret !== ''
  if (kept && !typed) return issues
  if (account.includes(':')) {
    issues.push({ field: fields.account, message: 'Das Konto darf keinen Doppelpunkt enthalten' })
  } else if (account.trim() === '') {
    issues.push({ field: fields.account, message: 'Bitte das Konto eingeben' })
  }
  if (secret === '') {
    issues.push({ field: fields.secret, message: `Bitte das ${fields.secretLabel} eingeben` })
  }
  return issues
}
