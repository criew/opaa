import { describe, expect, it } from 'vitest'
import type { InstallationState, InstallationStep } from '../api/types'
import {
  nextView,
  openRequiredSteps,
  previousView,
  resumeView,
  stepMeta,
  stepStatus,
} from './steps'

function step(
  id: InstallationStep['step'],
  required: boolean,
  saved = false,
  checked = false,
): InstallationStep {
  return { step: id, required, saved, checked, values: null }
}

const state = (steps: InstallationStep[]): InstallationState => ({ steps })

describe('installation steps', () => {
  it('resumes at the first required step whose check is owed', () => {
    const installation = state([
      step('FIRST_ADMIN', true, true, true),
      step('SIGN_IN', true, true, false),
      step('CHAT_MODEL', true),
      step('BRANDING', false),
    ])
    expect(resumeView(installation)).toBe('SIGN_IN')
  })

  it('resumes at the summary once every required step is checked, open optional ones aside', () => {
    const installation = state([step('FIRST_ADMIN', true, true, true), step('BRANDING', false)])
    expect(resumeView(installation)).toBe('SUMMARY')
  })

  it('walks the API order between token and summary', () => {
    const installation = state([step('FIRST_ADMIN', true), step('BRANDING', false)])
    expect(nextView(installation, 'FIRST_ADMIN')).toBe('BRANDING')
    expect(nextView(installation, 'BRANDING')).toBe('SUMMARY')
    expect(nextView(installation, 'SUMMARY')).toBe('SUMMARY')
    expect(previousView(installation, 'BRANDING')).toBe('FIRST_ADMIN')
    // The token is never a way back: it was accepted, and entering it again changes nothing.
    expect(previousView(installation, 'FIRST_ADMIN')).toBeNull()
  })

  it('names the required steps still open', () => {
    const installation = state([
      step('FIRST_ADMIN', true, true, true),
      step('CHAT_MODEL', true, true, false),
      step('MAIL', false),
    ])
    expect(openRequiredSteps(installation).map((s) => s.step)).toEqual(['CHAT_MODEL'])
  })

  it('tells a stored but unchecked step apart from an open one', () => {
    expect(stepStatus(step('CHAT_MODEL', true, true, false))).toBe('unchecked')
    expect(stepStatus(step('CHAT_MODEL', true))).toBe('open')
    expect(stepStatus(step('CHAT_MODEL', true, true, true))).toBe('checked')
  })

  it('says where an optional step can be changed later', () => {
    expect(stepMeta(true, 'Modelle')).toBe('Pflicht')
    expect(stepMeta(false, 'E-Mail')).toBe('Optional · später unter E-Mail')
    expect(stepMeta(false, null)).toBe('Optional')
  })
})
