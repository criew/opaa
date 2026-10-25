import { useCallback, useState } from 'react'
import {
  checkInstallationStep,
  completeInstallation,
  getInstallation,
  saveInstallationStep,
} from '../api/installationApi'
import type { InstallationState, StepInput, SupportedStepId } from '../api/types'
import { installationFailure, type InstallationFailure } from '../utils/installationErrors'
import { nextView, resumeView, type WizardView } from '../utils/steps'

export type WizardPhase = 'token' | 'steps' | 'completed' | 'installed'

/** The outcome of an action; a failure carries the sentence and the field errors to show. */
export type WizardResult = { ok: true } | { ok: false; failure: InstallationFailure }

interface WizardState {
  phase: WizardPhase
  /** Held in memory only: a reload asks for it again and resumes from the API's state. */
  token: string | null
  installation: InstallationState | null
  view: WizardView
  /** Why the assistant returned to the token, e.g. after the operator replaced it. */
  tokenNotice: string | null
}

const INITIAL: WizardState = {
  phase: 'token',
  token: null,
  installation: null,
  view: 'TOKEN',
  tokenNotice: null,
}

const SAVE_FAILED = 'Die Angaben konnten nicht gespeichert werden.'
const CHECK_FAILED = 'Die Prüfung ist fehlgeschlagen.'
const COMPLETE_FAILED = 'Die Installation konnte nicht abgeschlossen werden.'
const LOAD_FAILED = 'Der Stand der Einrichtung konnte nicht geladen werden.'

/**
 * The assistant's flow on the installation API. The API's state is the only record of progress:
 * every action ends with a fresh read of it, so the step list never shows a status the backend
 * does not hold, and a reopened assistant continues where the stored state says.
 */
export function useInstallationWizard() {
  const [state, setState] = useState<WizardState>(INITIAL)

  /** A 401 or 404 ends the step flow; the token view or the "already installed" view follows. */
  const leaveOn = useCallback((failure: InstallationFailure): boolean => {
    if (failure.kind === 'token') {
      setState({ ...INITIAL, tokenNotice: failure.message })
      return true
    }
    if (failure.kind === 'installed') {
      setState({ ...INITIAL, phase: 'installed' })
      return true
    }
    return false
  }, [])

  const submitToken = useCallback(async (token: string): Promise<WizardResult> => {
    try {
      const installation = await getInstallation(token)
      setState({
        phase: 'steps',
        token,
        installation,
        view: resumeView(installation),
        tokenNotice: null,
      })
      return { ok: true }
    } catch (err) {
      const failure = installationFailure(err, LOAD_FAILED)
      if (failure.kind === 'installed') setState({ ...INITIAL, phase: 'installed' })
      return { ok: false, failure }
    }
  }, [])

  /** Re-reads the API's state after an action; `false` when the flow had to be left. */
  const refresh = useCallback(
    async (token: string, view: (installation: InstallationState) => WizardView) => {
      try {
        const installation = await getInstallation(token)
        setState((current) => ({ ...current, installation, view: view(installation) }))
        return true
      } catch (err) {
        return !leaveOn(installationFailure(err, LOAD_FAILED))
      }
    },
    [leaveOn],
  )

  const submitStep = useCallback(
    async <S extends SupportedStepId>(step: S, values: StepInput[S]): Promise<WizardResult> => {
      const token = state.token
      if (!token) return { ok: false, failure: { kind: 'token', message: '', fieldErrors: [] } }
      try {
        await saveInstallationStep(token, step, values)
      } catch (err) {
        const failure = installationFailure(err, SAVE_FAILED)
        if (!leaveOn(failure)) await refresh(token, () => step)
        return { ok: false, failure }
      }
      try {
        await checkInstallationStep(token, step)
      } catch (err) {
        const failure = installationFailure(err, CHECK_FAILED)
        if (!leaveOn(failure)) await refresh(token, () => step)
        return { ok: false, failure }
      }
      await refresh(token, (installation) => nextView(installation, step))
      return { ok: true }
    },
    [state.token, leaveOn, refresh],
  )

  const complete = useCallback(async (): Promise<WizardResult> => {
    const token = state.token
    if (!token) return { ok: false, failure: { kind: 'token', message: '', fieldErrors: [] } }
    try {
      await completeInstallation(token)
      setState({ ...INITIAL, phase: 'completed' })
      return { ok: true }
    } catch (err) {
      const failure = installationFailure(err, COMPLETE_FAILED)
      if (!leaveOn(failure)) await refresh(token, () => 'SUMMARY')
      return { ok: false, failure }
    }
  }, [state.token, leaveOn, refresh])

  const goTo = useCallback((view: WizardView) => {
    setState((current) => ({ ...current, view }))
  }, [])

  return { ...state, submitToken, submitStep, complete, goTo }
}
