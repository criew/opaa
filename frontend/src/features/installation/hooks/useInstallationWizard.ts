import { useCallback, useState } from 'react'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import {
  checkInstallationStep,
  completeInstallation,
  discoverInstallationModels,
  getInstallation,
  saveInstallationStep,
} from '../api/installationApi'
import type {
  InstallationModelDiscoveryRequest,
  InstallationStatusResponse,
  InstallationStepId,
  ModelStepId,
  StepInput,
} from '../api/types'
import {
  discoveryLimitMessage,
  installationFailure,
  type InstallationFailure,
} from '../utils/installationErrors'
import { nextView, resumeView, type WizardView } from '../utils/steps'

export type WizardPhase = 'token' | 'steps' | 'completed' | 'installed'

/** The outcome of an action; a failure carries the sentence and the field errors to show. */
export type WizardResult = { ok: true } | { ok: false; failure: InstallationFailure }

interface WizardState {
  phase: WizardPhase
  /** Held in memory only: a reload asks for it again and resumes from the API's state. */
  token: string | null
  installation: InstallationStatusResponse | null
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
const DISCOVERY_FAILED = 'Die Modelle konnten nicht abgerufen werden.'

function failed(message: string): WizardResult {
  return { ok: false, failure: { kind: 'other', message, fieldErrors: [] } }
}

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

  /** Re-reads the API's state after an action and shows the view it picks. */
  const refresh = useCallback(
    async (token: string, view: (installation: InstallationStatusResponse) => WizardView) => {
      try {
        const installation = await getInstallation(token)
        setState((current) => ({ ...current, installation, view: view(installation) }))
      } catch (err) {
        leaveOn(installationFailure(err, LOAD_FAILED))
      }
    },
    [leaveOn],
  )

  /**
   * Saves the step (unless `values` is `null`: nothing changed, only check again) and checks it.
   * A failed check keeps the step open and returns the backend's sentence about it.
   */
  const submitStep = useCallback(
    async <S extends InstallationStepId>(
      step: S,
      values: StepInput[S] | null,
    ): Promise<WizardResult> => {
      const token = state.token
      if (!token) return { ok: false, failure: { kind: 'token', message: '', fieldErrors: [] } }
      let result: WizardResult = { ok: true }
      try {
        if (values) await saveInstallationStep(token, step, values)
        const checked = await checkInstallationStep(token, step)
        if (!checked.checked) result = failed(checked.checkMessage ?? CHECK_FAILED)
      } catch (err) {
        const failure = installationFailure(err, values ? SAVE_FAILED : CHECK_FAILED)
        if (leaveOn(failure)) return { ok: false, failure }
        result = { ok: false, failure }
      }
      await refresh(token, (installation) => (result.ok ? nextView(installation, step) : step))
      return result
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

  /**
   * The model list of a model step's endpoint. A wrong token or a completed installation leaves
   * the step flow as any other action does; every other failure is the error the step shows.
   */
  const discoverModels = useCallback(
    async (
      step: ModelStepId,
      request: InstallationModelDiscoveryRequest,
    ): Promise<LlmModelDiscoveryResponse> => {
      const token = state.token
      if (!token) throw new Error(DISCOVERY_FAILED)
      try {
        return await discoverInstallationModels(token, step, request)
      } catch (err) {
        const failure = installationFailure(err, DISCOVERY_FAILED)
        leaveOn(failure)
        throw new Error(discoveryLimitMessage(err) ?? failure.message, { cause: err })
      }
    },
    [state.token, leaveOn],
  )

  const goTo = useCallback((view: WizardView) => {
    setState((current) => ({ ...current, view }))
  }, [])

  return { ...state, submitToken, submitStep, discoverModels, complete, goTo }
}
