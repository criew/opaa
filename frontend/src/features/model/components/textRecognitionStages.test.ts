import { describe, expect, it } from 'vitest'
import type { TextRecognitionTestResponse } from '@/shared/api/types'
import { failedStage, stageStates } from './textRecognitionStages'

function result(
  outcome: TextRecognitionTestResponse['outcome'],
  stage: TextRecognitionTestResponse['failedStage'],
) {
  return {
    kind: 'outcome' as const,
    response: { success: outcome === 'READY', outcome, failedStage: stage, message: 'x' },
  }
}

describe('textRecognitionStages', () => {
  it('takes the failed stage from the server, not from the outcome', () => {
    // An unreachable service while reading the test page fails recognition, not the address.
    expect(failedStage(result('UNREACHABLE', 'RECOGNITION'))).toBe('RECOGNITION')
    expect(failedStage(result('UNREACHABLE', 'ADDRESS'))).toBe('ADDRESS')
    expect(failedStage(result('KEY_REJECTED', 'AUTHENTICATION'))).toBe('AUTHENTICATION')
    expect(failedStage(result('READY', null))).toBeNull()
  })

  it('fails the address stage when the address check refused the address', () => {
    expect(failedStage({ kind: 'addressRefused', message: 'gesperrt' })).toBe('ADDRESS')
  })

  it('marks stages before the failed one as passed and those after it as not reached', () => {
    expect(stageStates(result('KEY_REJECTED', 'AUTHENTICATION'))).toEqual({
      ADDRESS: 'passed',
      AUTHENTICATION: 'failed',
      RECOGNITION: 'skipped',
    })
    expect(stageStates(result('READY', null))).toEqual({
      ADDRESS: 'passed',
      AUTHENTICATION: 'passed',
      RECOGNITION: 'passed',
    })
    expect(stageStates(result('UNREACHABLE', 'ADDRESS'))).toEqual({
      ADDRESS: 'failed',
      AUTHENTICATION: 'skipped',
      RECOGNITION: 'skipped',
    })
  })
})
