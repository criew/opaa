import type { AgentRunStep, AgentRunStreamStep } from '@/shared/api/types'

/** One word per kind of step; a tool name is a technical constant and never shown itself. */
const STEP_NAMES: Record<string, string> = {
  search_knowledge: 'Suche',
  read_document: 'Lesen',
  list_sources: 'Übersicht',
  run_code: 'Berechnung',
  ask_user: 'Rückfrage',
  fill_template: 'Vorlage',
}

const UNKNOWN_STEP = 'Werkzeug'

/** One word per outcome; a step that ran and delivered needs none. */
const STEP_STATUS: Record<AgentRunStep['status'], string | null> = {
  DONE: null,
  EMPTY: 'Leer',
  ARGUMENT_IGNORED: 'Eingegrenzt',
  INVALID_ARGUMENTS: 'Ungültig',
  UNKNOWN_TOOL: 'Unbekannt',
  DENIED: 'Verweigert',
  LIMIT: 'Grenze',
  ERROR: 'Fehler',
}

export const RUNNING_STEP_STATUS = 'Läuft'

export function stepName(tool: string): string {
  return STEP_NAMES[tool] ?? UNKNOWN_STEP
}

/**
 * A step as one line: its name, what it was about, its hits and its outcome, e.g.
 * "Suche: Dienstvereinbarung · 4 Treffer". A streamed step without status still runs.
 */
export function stepText(step: AgentRunStep | AgentRunStreamStep): string {
  const parts = [step.detail ? `${stepName(step.tool)}: ${step.detail}` : stepName(step.tool)]
  if (step.hits != null) parts.push(`${step.hits} Treffer`)
  const status = step.status ? STEP_STATUS[step.status] : RUNNING_STEP_STATUS
  if (status) parts.push(status)
  return parts.join(' · ')
}

/** Longest sub-question the status line names; the step list shows it whole. */
const STATUS_DETAIL_CHARS = 60

function shortened(detail: string): string {
  return detail.length <= STATUS_DETAIL_CHARS
    ? detail
    : `${detail.slice(0, STATUS_DETAIL_CHARS - 1).trimEnd()}…`
}

/** What the agent is doing while `step` runs, for the status line of the running answer. */
export function stepActivity(step: AgentRunStreamStep): string {
  switch (step.tool) {
    case 'search_knowledge':
      return step.detail ? `Sucht nach „${shortened(step.detail)}“ …` : 'Sucht …'
    case 'read_document':
      return 'Liest ein Dokument …'
    case 'list_sources':
      return 'Sieht die Quellen durch …'
    case 'run_code':
      return 'Rechnet …'
    case 'ask_user':
      return 'Formuliert eine Rückfrage …'
    default:
      return 'Arbeitet …'
  }
}
