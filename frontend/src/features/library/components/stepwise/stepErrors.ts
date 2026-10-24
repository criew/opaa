import type { CheckOutcome, FieldErrors, FieldSpec, StepwiseSource } from './types'

/** The id of a field's input, also the target of the error summary's links. */
export function fieldId(idPrefix: string, key: string) {
  return `${idPrefix}-field-${key}`
}

/** The messages a failed check puts on the fields of its stage. */
export function stageErrors<V>(
  stepwise: StepwiseSource<V>,
  specs: FieldSpec[],
  outcome: CheckOutcome | null,
): FieldErrors {
  if (!outcome || outcome.ok) return {}
  const present = new Set(specs.map((spec) => spec.key))
  const errors: FieldErrors = {}
  if (outcome.stages.address === 'failed') {
    for (const key of stepwise.stageFields.address) {
      if (present.has(key)) errors[key] = 'Unter dieser Adresse antwortet keine Quelle.'
    }
  }
  if (outcome.stages.signIn === 'failed') {
    for (const key of stepwise.stageFields.signIn) {
      if (present.has(key)) errors[key] = 'Die Quelle hat die Anmeldung abgelehnt.'
    }
  }
  return errors
}

/** The sentence of a failed check for the error summary. */
export function checkSummary(outcome: CheckOutcome): string {
  if (outcome.stages.address === 'failed') return 'Die Adresse ist nicht erreichbar.'
  if (outcome.stages.signIn === 'failed') return 'Die Anmeldung wurde abgelehnt.'
  if (outcome.stages.rights === 'failed') return 'Der Quelle fehlen Leserechte.'
  return 'Ein Teil der Auswahl ist nicht lesbar.'
}
