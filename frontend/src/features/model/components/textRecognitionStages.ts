import type { TextRecognitionTestResponse } from '@/shared/api/types'

export type TestStage = NonNullable<TextRecognitionTestResponse['failedStage']>
export type TestStageState = 'passed' | 'failed' | 'skipped'

export const TEST_STAGES: readonly TestStage[] = ['ADDRESS', 'AUTHENTICATION', 'RECOGNITION']

export const TEST_STAGE_LABELS: Record<TestStage, string> = {
  ADDRESS: 'Adresse',
  AUTHENTICATION: 'Anmeldung',
  RECOGNITION: 'Erkennung',
}

/**
 * The connection test as it is shown: one state per stage, and the message at the stage that
 * failed. A refused address (field error from the address check) never reached the service.
 */
export type TextRecognitionTestView =
  | { kind: 'outcome'; response: TextRecognitionTestResponse }
  | { kind: 'addressRefused'; message: string }

/** The stage at which a test stopped, as the server names it; null when every stage passed. */
export function failedStage(view: TextRecognitionTestView): TestStage | null {
  if (view.kind === 'addressRefused') return 'ADDRESS'
  return view.response.failedStage ?? null
}

/** Stages before the failed one passed, the failed one failed, those after it were not reached. */
export function stageStates(view: TextRecognitionTestView): Record<TestStage, TestStageState> {
  const failed = failedStage(view)
  const failedIndex = failed === null ? TEST_STAGES.length : TEST_STAGES.indexOf(failed)
  return Object.fromEntries(
    TEST_STAGES.map((stage, index) => [
      stage,
      index < failedIndex ? 'passed' : index === failedIndex ? 'failed' : 'skipped',
    ]),
  ) as Record<TestStage, TestStageState>
}
