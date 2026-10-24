import { sameLibrarySourceOrigin } from '../../../utils/librarySourceConfig'
import type { SourceFormContext } from '../../sources/types'
import type { SourceIssue } from '../../../utils/sourceIssue'
import type { CheckState } from '../setupTypes'

/** The state of the connection check in one word, for the summary of „Verbindung“. */
export function checkWord(state: CheckState): string | null {
  switch (state) {
    case 'passed':
      return 'verbunden'
    case 'partial':
      return 'teilweise lesbar'
    case 'failed':
      return 'Test fehlgeschlagen'
    case 'checking':
      return 'wird geprüft'
    default:
      return null
  }
}

/** Host (and port) of an address, the address itself when it is none. */
export function hostOf(address: string): string {
  try {
    return new URL(address.trim()).host || address.trim()
  } catch {
    return address.trim()
  }
}

/** Joins the non-empty parts of a summary. */
export function joinSummary(...parts: (string | null | undefined | false)[]): string | null {
  const kept = parts.filter((part): part is string => Boolean(part))
  return kept.length > 0 ? kept.join(' · ') : null
}

/** Collects issues; `at(field, message)` adds one when `when` holds. */
export function issueList() {
  const list: SourceIssue[] = []
  return {
    at(field: string, message: string, when = true) {
      if (when && !list.some((issue) => issue.field === field)) list.push({ field, message })
    },
    list,
  }
}

/** The address rule every web source shares. */
export function addressIssue(address: string): string | null {
  const url = address.trim()
  if (url === '') return 'Adresse fehlt'
  if (!/^https?:\/\//i.test(url)) return 'Adresse beginnt mit http:// oder https://'
  return null
}

/** „1 Ordner“, „12 Spaces“. */
export function counted(count: number, [one, many]: [string, string]): string {
  return `${count} ${count === 1 ? one : many}`
}

/** Whether stored credentials still serve the values: edit mode, same server. */
export function credentialsKept(context: SourceFormContext, sourceUrl: string): boolean {
  return context.credentialsStored && sameLibrarySourceOrigin(context.originalSourceUrl, sourceUrl)
}

/** The account-and-secret rules of every connector that signs in with both. */
export function pairIssues(
  add: (field: string, message: string, when?: boolean) => void,
  [accountField, secretField]: [string, string],
  account: string,
  secret: string,
  kept: boolean,
  accountName = 'Kennung',
  secretName = 'Passwort',
) {
  add(accountField, `${accountName} ohne Doppelpunkt angeben`, account.includes(':'))
  if (!kept || account !== '' || secret !== '') {
    add(accountField, `${accountName} fehlt`, account.trim() === '')
    add(secretField, `${secretName} fehlt`, secret === '')
  }
}

export function folderIssues(
  add: (field: string, message: string, when?: boolean) => void,
  folders: string[],
  max: number,
) {
  add('folders', 'Mindestens einen Ordner wählen, / für alles', folders.length === 0)
  add('folders', `Höchstens ${max} Ordner`, folders.length > max)
  add('folders', 'Ein Ordnerpfad enthält kein Komma', folders.some((f) => f.includes(',')))
}

