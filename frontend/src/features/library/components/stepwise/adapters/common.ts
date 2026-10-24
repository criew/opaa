import type { FieldErrors, ScopeNode, StepContext, SummaryRow } from '../types'

/** Whether the stored credentials stand: edit mode, stored, and „Ersetzen“ not chosen. */
export function credentialsKept(ctx: StepContext) {
  return ctx.mode === 'edit' && ctx.credentialsStored && !ctx.replacingCredentials
}

/** Whether the page asks for credentials of the library's own at all. */
export function asksCredentials(ctx: StepContext) {
  return ctx.fields.asksSecret && !credentialsKept(ctx)
}

/** The address rule every network source shares: present and starting with http(s). */
export function addressError(value: string, errors: FieldErrors, key = 'sourceUrl') {
  const url = value.trim()
  if (url === '') errors[key] = 'Bitte die Adresse eingeben.'
  else if (!/^https?:\/\/[^\s/]+/i.test(url)) {
    errors[key] = 'Die Adresse beginnt mit https:// oder http://.'
  }
}

export function requireField(value: string, errors: FieldErrors, key: string, message: string) {
  if (value.trim() === '') errors[key] = message
}

/** A folder node of a path tree („/a/b“): its ancestors are the paths above it. */
export function pathNode(path: string, name?: string | null): ScopeNode {
  const segments = path.split('/').filter(Boolean)
  const ancestors = segments.slice(0, -1).map((_, i) => `/${segments.slice(0, i + 1).join('/')}`)
  return {
    id: path,
    name: name ?? segments[segments.length - 1] ?? '/',
    ancestors,
    trail: segments.slice(0, -1),
    expandable: true,
    selectable: true,
  }
}

/** The lines of a multi-line field, trimmed, without blanks and duplicates. */
export function linesOf(text: string): string[] {
  return [
    ...new Set(
      text
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    ),
  ]
}

export function credentialsRow(stored: boolean, entered: boolean): SummaryRow {
  return {
    label: 'Zugangsdaten',
    value: entered ? 'Eingegeben' : stored ? 'Gesetzt' : 'Keine',
  }
}

export function countLabel(count: number, [singular, plural]: [string, string]) {
  return `${count} ${count === 1 ? singular : plural}`
}
