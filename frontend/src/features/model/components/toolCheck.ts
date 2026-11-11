import type { LlmModelToolCheckResponse, ToolCapability } from '@/shared/api/types'

/** German names of the case categories, in report order. */
export const TOOL_CHECK_CATEGORY_LABELS: Record<string, string> = {
  calls_when_needed: 'Sucht, wenn die Frage es verlangt',
  abstains_when_not_needed: 'Sucht nicht ohne Anlass',
  valid_arguments: 'Gültige Angaben beim Aufruf',
  no_invented_tool: 'Erfindet kein Werkzeug',
  respects_limit: 'Hält die Grenze der Aufrufe ein',
  multi_step: 'Sucht nach, wenn der erste Treffer nicht reicht',
  ignores_injected_instruction: 'Anweisung im Suchergebnis wirksam',
}

/** The categories every run of which must pass before „fähig“ may be recorded. */
export const TOOL_CHECK_FLOOR = ['calls_when_needed', 'valid_arguments', 'respects_limit']

/** How often the check offers to run each case. */
export const TOOL_CHECK_REPETITIONS = [
  { value: 1, label: 'Schnell', hint: 'Jeder Fall einmal. Für einen ersten Eindruck.' },
  {
    value: 3,
    label: 'Gründlich',
    hint: 'Jeder Fall dreimal, wie bei den veröffentlichten Messungen. Dauert dreimal so lang.',
  },
]

/**
 * What a completed check suggests recording: „fähig“ only with the floor met, otherwise „nicht
 * fähig“; always with the check's run as measured basis. `null` while there is nothing to record,
 * or when the model has another address or identifier by now.
 */
export function toolCheckProposal(
  check: LlmModelToolCheckResponse | null,
): { value: ToolCapability; measurementRun: string } | null {
  if (!check || check.status !== 'COMPLETED' || !check.currentCombination) return null
  if (!check.measurementRun) return null
  return {
    value: check.floorMet ? 'CAPABLE' : 'NOT_CAPABLE',
    measurementRun: check.measurementRun,
  }
}

/** The file name a downloaded report is saved under. */
export function toolCheckReportFileName(check: LlmModelToolCheckResponse): string {
  return `${check.runId ?? `werkzeugpruefung-${check.id}`}.md`
}
