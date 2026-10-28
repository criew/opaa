import type {
  InstallationStatusResponse,
  InstallationStepId,
  InstallationStepState,
} from '../api/types'

/** What the assistant shows: the token, one of the API's steps, or the summary. */
export type WizardView = 'TOKEN' | InstallationStepId | 'SUMMARY'

interface StepInfo {
  label: string
  /** Where the value can be changed after the installation; `null` where it cannot. */
  laterUnder: string | null
}

export const STEP_INFO: Record<InstallationStepId, StepInfo> = {
  FIRST_ADMIN: { label: 'Erstes Konto', laterUnder: 'Benutzer' },
  SIGN_IN: { label: 'Anmeldeverfahren', laterUnder: 'Identitätsanbieter' },
  CHAT_MODEL: { label: 'Chat-Modell', laterUnder: 'Modelle' },
  EMBEDDING_MODEL: { label: 'Embedding-Modell', laterUnder: 'Modelle' },
  MAIL: { label: 'E-Mail-Versand', laterUnder: 'E-Mail' },
}

export const TOKEN_LABEL = 'Installations-Token'
export const SUMMARY_LABEL = 'Abschluss'

/** All views in the order of the assistant: token, the API's steps, summary. */
export function viewOrder(state: InstallationStatusResponse): WizardView[] {
  return ['TOKEN', ...state.steps.map((s) => s.step), 'SUMMARY']
}

/**
 * Where a reopened assistant continues: the first step whose check is still owed - a mandatory one,
 * or an optional one with saved values -, otherwise the summary. An optional step never saved is
 * not revisited on its own - skipping one is not stored, and it stays reachable from the step list.
 */
export function resumeView(state: InstallationStatusResponse): WizardView {
  return openSteps(state)[0]?.step ?? 'SUMMARY'
}

/** The view after `current`; the summary is the last one. */
export function nextView(state: InstallationStatusResponse, current: WizardView): WizardView {
  const order = viewOrder(state)
  const index = order.indexOf(current)
  return order[Math.min(index + 1, order.length - 1)]
}

/** The view before `current`; the first step has none (the token cannot be re-entered). */
export function previousView(
  state: InstallationStatusResponse,
  current: WizardView,
): WizardView | null {
  const order = viewOrder(state)
  const index = order.indexOf(current)
  return index > 1 ? order[index - 1] : null
}

export function findStep(
  state: InstallationStatusResponse,
  step: InstallationStepId,
): InstallationStepState | undefined {
  return state.steps.find((s) => s.step === step)
}

/**
 * The steps that hold up the completion, as the backend decides it: a mandatory step until it is
 * checked, an optional one while it is saved but not checked (check it again or skip it).
 */
export function openSteps(state: InstallationStatusResponse): InstallationStepState[] {
  return state.steps.filter((s) => (s.mandatory ? !s.checked : s.saved && !s.checked))
}

export type StepStatus = 'checked' | 'failed' | 'unchecked' | 'open' | 'notSet'

/** An optional step without saved values is no gap: the completion leaves it out. */
export function stepStatus(step: InstallationStepState): StepStatus {
  if (step.checked) return 'checked'
  if (!step.saved) return step.mandatory ? 'open' : 'notSet'
  return step.checkMessage ? 'failed' : 'unchecked'
}

export const STEP_STATUS_LABEL: Record<StepStatus, string> = {
  checked: 'Geprüft',
  failed: 'Prüfung fehlgeschlagen',
  unchecked: 'Gespeichert, Prüfung offen',
  open: 'Offen',
  notSet: 'Nicht eingerichtet',
}

export const STEP_STATUS_TONE = {
  checked: 'success',
  failed: 'error',
  unchecked: 'warning',
  open: 'neutral',
  notSet: 'neutral',
} as const satisfies Record<StepStatus, string>

/** The meta line beside a step's title: mandatory or optional, and where it can be changed. */
export function stepMeta(mandatory: boolean, laterUnder?: string | null): string {
  if (mandatory) return 'Pflicht'
  return laterUnder ? `Optional · später unter ${laterUnder}` : 'Optional'
}
