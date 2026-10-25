import type { InstallationState, InstallationStep, InstallationStepId } from '../api/types'

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
  EMBEDDING_MODEL: { label: 'Embedding-Modell', laterUnder: null },
  OUTBOUND_PROXY: { label: 'Ausgangsproxy', laterUnder: 'Anbindungen' },
  MAIL: { label: 'E-Mail-Versand', laterUnder: 'E-Mail' },
  BRANDING: { label: 'Branding', laterUnder: 'Allgemein & Branding' },
}

export const TOKEN_LABEL = 'Installations-Token'
export const SUMMARY_LABEL = 'Abschluss'

/** All views in the order of the assistant: token, the API's steps, summary. */
export function viewOrder(state: InstallationState): WizardView[] {
  return ['TOKEN', ...state.steps.map((s) => s.step), 'SUMMARY']
}

/**
 * Where a reopened assistant continues: the first required step whose check is still owed,
 * otherwise the summary. Optional steps are not revisited on their own - skipping one is not
 * stored, and they stay reachable from the step list.
 */
export function resumeView(state: InstallationState): WizardView {
  return state.steps.find((s) => s.required && !s.checked)?.step ?? 'SUMMARY'
}

/** The view after `current`; the summary is the last one. */
export function nextView(state: InstallationState, current: WizardView): WizardView {
  const order = viewOrder(state)
  const index = order.indexOf(current)
  return order[Math.min(index + 1, order.length - 1)]
}

/** The view before `current`; the first step has none (the token cannot be re-entered). */
export function previousView(state: InstallationState, current: WizardView): WizardView | null {
  const order = viewOrder(state)
  const index = order.indexOf(current)
  return index > 1 ? order[index - 1] : null
}

export function findStep(
  state: InstallationState,
  step: InstallationStepId,
): InstallationStep | undefined {
  return state.steps.find((s) => s.step === step)
}

/** The required steps whose check is still owed; the installation completes only without any. */
export function openRequiredSteps(state: InstallationState): InstallationStep[] {
  return state.steps.filter((s) => s.required && !s.checked)
}

export type StepStatus = 'checked' | 'unchecked' | 'open'

export function stepStatus(step: InstallationStep): StepStatus {
  if (step.checked) return 'checked'
  return step.saved ? 'unchecked' : 'open'
}

export const STEP_STATUS_LABEL: Record<StepStatus, string> = {
  checked: 'Geprüft',
  unchecked: 'Gespeichert, Prüfung offen',
  open: 'Offen',
}

/** The meta line beside a step's title: required or optional, and where it can be changed. */
export function stepMeta(required: boolean, laterUnder?: string | null): string {
  if (required) return 'Pflicht'
  return laterUnder ? `Optional · später unter ${laterUnder}` : 'Optional'
}
