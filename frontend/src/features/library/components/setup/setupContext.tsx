import { createContext, useContext, type ReactNode } from 'react'
import type { SourceIssue } from '../../utils/sourceIssue'

/** The four sections every source form shows, always in this order. */
export type SetupSectionKey = 'connection' | 'credentials' | 'scope' | 'schedule'

export const SETUP_SECTION_TITLES: Record<SetupSectionKey, string> = {
  connection: 'Verbindung',
  credentials: 'Zugangsdaten',
  scope: 'Umfang',
  schedule: 'Zeitplan',
}

/** What a connection check reports when the surrounding step or dialog runs it before moving on. */
export type CheckOutcome = 'passed' | 'failed' | 'none'

/** The check a form registers so „Weiter“ and „Speichern“ can run it once more. */
export interface RegisteredCheck {
  ensure: () => Promise<CheckOutcome>
}

export interface SetupContextValue {
  /** Prefix of every field id; an issue's field `x` lives at `fieldId(idPrefix, x)`. */
  idPrefix: string
  /** All issues of the last submit, shown at their fields and in the summary. */
  issues: SourceIssue[]
  /** Zugang and consent, rendered first in the section „Verbindung“. */
  connectionLead?: ReactNode
  /** The schedule section, rendered by the step or dialog as the fourth section. */
  schedule?: ReactNode
  /** Called by the connection check so the surrounding flow can run it before moving on. */
  registerCheck?: (check: RegisteredCheck | null) => void
}

export const SetupContext = createContext<SetupContextValue>({ idPrefix: 'setup', issues: [] })

export function useSetup(): SetupContextValue {
  return useContext(SetupContext)
}

/** The DOM id of the field a form value is shown in. */
export function fieldId(idPrefix: string, field: string): string {
  return `${idPrefix}-${field.replace(/[^a-zA-Z0-9]+/g, '-')}`
}

/** The message of the first issue naming `field`, null when it has none. */
export function issueMessage(issues: SourceIssue[], field: string): string | null {
  return issues.find((issue) => issue.field === field)?.message ?? null
}

/** Collects the issues of independent checks; a null result is no issue. */
export function collectIssues(...checks: (SourceIssue | null | undefined)[]): SourceIssue[] {
  return checks.filter((issue): issue is SourceIssue => Boolean(issue))
}
