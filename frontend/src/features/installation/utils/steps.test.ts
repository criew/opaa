import { describe, expect, it } from 'vitest'
import type { InstallationStatusResponse, InstallationStepState } from '../api/types'
import { nextView, openSteps, previousView, resumeView, stepMeta, stepStatus } from './steps'

function step(
  id: InstallationStepState['step'],
  mandatory: boolean,
  saved = false,
  checked = false,
  checkMessage: string | null = null,
): InstallationStepState {
  return { step: id, mandatory, saved, checked, checkMessage }
}

function state(steps: InstallationStepState[]): InstallationStatusResponse {
  return {
    pending: true,
    completable: steps.every((s) => !s.mandatory || s.checked),
    publicBaseUrlConfigured: true,
    steps,
  }
}

describe('installation steps', () => {
  it('resumes at the first mandatory step whose check is owed', () => {
    const installation = state([
      step('FIRST_ADMIN', true, true, true),
      step('SIGN_IN', true, true, false),
      step('CHAT_MODEL', true),
    ])
    expect(resumeView(installation)).toBe('SIGN_IN')
  })

  it('resumes at the summary once every mandatory step is checked, open optional ones aside', () => {
    const installation = state([step('FIRST_ADMIN', true, true, true), step('CHAT_MODEL', false)])
    expect(resumeView(installation)).toBe('SUMMARY')
  })

  it('walks the API order between token and summary', () => {
    const installation = state([step('FIRST_ADMIN', true), step('CHAT_MODEL', false)])
    expect(nextView(installation, 'FIRST_ADMIN')).toBe('CHAT_MODEL')
    expect(nextView(installation, 'CHAT_MODEL')).toBe('SUMMARY')
    expect(nextView(installation, 'SUMMARY')).toBe('SUMMARY')
    expect(previousView(installation, 'CHAT_MODEL')).toBe('FIRST_ADMIN')
    // The token is never a way back: it was accepted, and entering it again changes nothing.
    expect(previousView(installation, 'FIRST_ADMIN')).toBeNull()
  })

  it('names the mandatory steps still open and a saved optional step not yet checked', () => {
    const installation = state([
      step('FIRST_ADMIN', true, true, true),
      step('CHAT_MODEL', true, true, false),
      step('SIGN_IN', false),
    ])
    expect(openSteps(installation).map((s) => s.step)).toEqual(['CHAT_MODEL'])
    const failedMail = state([
      step('FIRST_ADMIN', true, true, true),
      step('MAIL', false, true, false, 'Verbindung abgelehnt'),
    ])
    expect(openSteps(failedMail).map((s) => s.step)).toEqual(['MAIL'])
  })

  it('shows an optional step without saved values as not set up, not as a gap', () => {
    expect(stepStatus(step('MAIL', false))).toBe('notSet')
    expect(stepStatus(step('MAIL', false, true, false, 'Verbindung abgelehnt'))).toBe('failed')
  })

  it('tells a failed check apart from a step that is stored but not yet checked', () => {
    expect(stepStatus(step('CHAT_MODEL', true, true, false, 'Verbindung abgelehnt'))).toBe('failed')
    expect(stepStatus(step('CHAT_MODEL', true, true, false))).toBe('unchecked')
    expect(stepStatus(step('CHAT_MODEL', true))).toBe('open')
    expect(stepStatus(step('CHAT_MODEL', true, true, true, 'Prüfung erfolgreich.'))).toBe('checked')
  })

  it('says where an optional step can be changed later', () => {
    expect(stepMeta(true, 'Modelle')).toBe('Pflicht')
    expect(stepMeta(false, 'E-Mail')).toBe('Optional · später unter E-Mail')
    expect(stepMeta(false, null)).toBe('Optional')
  })
})
