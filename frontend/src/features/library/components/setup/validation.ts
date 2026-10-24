import { issueAt, type SourceIssue } from '../../utils/sourceIssue'

/** Addresses: present and with a scheme the source takes. */
export function addressIssue(
  field: string,
  value: string,
  scheme: RegExp = /^https?:\/\//i,
  schemeHint = 'Die Adresse beginnt mit https:// oder http://.',
): SourceIssue | null {
  const trimmed = value.trim()
  if (trimmed === '') return issueAt(field, 'Bitte die Adresse eingeben.')
  if (!scheme.test(trimmed)) return issueAt(field, schemeHint)
  return null
}

/**
 * The account and its secret, each at its own field. `kept` means stored credentials stand in
 * and both may stay empty; then only a half-typed pair is an issue.
 */
export function accountIssues(
  user: { field: string; value: string },
  secret: { field: string; value: string; label?: string },
  kept: boolean,
): SourceIssue[] {
  const name = user.value.trim()
  const issues: SourceIssue[] = []
  if (name.includes(':'))
    issues.push(issueAt(user.field, 'Die Kennung darf keinen Doppelpunkt enthalten.'))
  if (kept && name === '' && secret.value === '') return issues
  if (name === '') issues.push(issueAt(user.field, 'Bitte die Kennung eingeben.'))
  if (secret.value === '') {
    issues.push(issueAt(secret.field, `Bitte ${secret.label ?? 'das Kennwort'} eingeben.`))
  }
  return issues
}

/** A folder list, one per line: at least one, at most `max`, no commas. */
export function folderListIssue(text: string, max: number): SourceIssue | null {
  const folders = text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  if (folders.length === 0) return issueAt('folders', 'Bitte mindestens einen Ordner wählen.')
  if (folders.length > max) return issueAt('folders', `Höchstens ${max} Ordner je Bibliothek.`)
  if (folders.some((folder) => folder.includes(','))) {
    return issueAt('folders', 'Ein Ordnerpfad darf kein Komma enthalten.')
  }
  return null
}
