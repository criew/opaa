import { describe, expect, it } from 'vitest'
import type { TextRecognitionTestResponse } from '@/shared/api/types'
import { failedStage, stageStates } from './textRecognitionStages'

function outcome(value: TextRecognitionTestResponse['outcome']) {
  return {
    kind: 'outcome' as const,
    response: { success: value === 'READY', outcome: value, message: 'x' },
  }
}

describe('textRecognitionStages', () => {
  it.each([
    ['READY', null],
    ['UNREACHABLE', 'ADDRESS'],
    ['KEY_REJECTED', 'LOGIN'],
    ['ENGINE_MISSING', 'RECOGNITION'],
    ['NO_TEXT', 'RECOGNITION'],
  ] as const)('maps the outcome %s to the failed stage %s', (value, stage) => {
    expect(failedStage(outcome(value))).toBe(stage)
  })

  it('fails the address stage when the address check refused the address', () => {
    expect(failedStage({ kind: 'addressRefused', message: 'gesperrt' })).toBe('ADDRESS')
  })

  it('marks stages before the failed one as passed and those after it as not reached', () => {
    expect(stageStates(outcome('KEY_REJECTED'))).toEqual({
      ADDRESS: 'passed',
      LOGIN: 'failed',
      RECOGNITION: 'skipped',
    })
    expect(stageStates(outcome('READY'))).toEqual({
      ADDRESS: 'passed',
      LOGIN: 'passed',
      RECOGNITION: 'passed',
    })
    expect(stageStates(outcome('UNREACHABLE'))).toEqual({
      ADDRESS: 'failed',
      LOGIN: 'skipped',
      RECOGNITION: 'skipped',
    })
  })
})
