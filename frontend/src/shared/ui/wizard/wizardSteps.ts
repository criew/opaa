/** The first step of every assistant: name and description of the new object. */
export const STEP_MASTER_DATA = 'Stammdaten'
/** The last step of every assistant: every answer with „Ändern“, then „Anlegen“. */
export const STEP_REVIEW = 'Prüfen'

/**
 * The steps of an assistant (guidelines 5.14): one word each, at least three, the first
 * „Stammdaten“ - optionally after a choice of type such as „Quellart“ - and the last „Prüfen“.
 * What needs fewer steps is a dialog.
 */
export type WizardSteps =
  | readonly [typeof STEP_MASTER_DATA, string, ...string[], typeof STEP_REVIEW]
  | readonly [string, typeof STEP_MASTER_DATA, ...string[], typeof STEP_REVIEW]
