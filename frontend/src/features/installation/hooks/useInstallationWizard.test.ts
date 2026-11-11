import { act, renderHook } from '@testing-library/react'
import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InstallationStatusResponse, InstallationStepState } from '../api/types'
import { useInstallationWizard } from './useInstallationWizard'

const api = vi.hoisted(() => ({
  getInstallation: vi.fn(),
  saveInstallationStep: vi.fn(),
  checkInstallationStep: vi.fn(),
}))

vi.mock('../api/installationApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/installationApi')>()),
  ...api,
}))

function status(textRecognition: Partial<InstallationStepState>): InstallationStatusResponse {
  return {
    steps: [
      { step: 'FIRST_ADMIN', mandatory: true, saved: true, checked: true },
      { step: 'SIGN_IN', mandatory: true, saved: true, checked: true },
      {
        step: 'TEXT_RECOGNITION_MODEL',
        mandatory: false,
        saved: true,
        checked: false,
        ...textRecognition,
      },
    ],
  } as InstallationStatusResponse
}

const VALUES = {
  connectionType: 'DOCLING_SERVE' as const,
  baseUrl: 'http://opaa-ocr:5001',
  model: null,
}

function clientTimeout() {
  return new AxiosError('timeout of 200000ms exceeded', 'ECONNABORTED')
}

async function signedIn() {
  const hook = renderHook(() => useInstallationWizard())
  await act(async () => {
    await hook.result.current.submitToken('token')
  })
  return hook
}

describe('useInstallationWizard on a client timeout of a check', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.saveInstallationStep.mockResolvedValue({})
  })

  it('moves on when the backend has meanwhile stored the passed check', async () => {
    api.getInstallation.mockResolvedValueOnce(status({}))
    const { result } = await signedIn()
    api.checkInstallationStep.mockRejectedValue(clientTimeout())
    api.getInstallation.mockResolvedValue(status({ checked: true }))

    let outcome
    await act(async () => {
      outcome = await result.current.submitStep('TEXT_RECOGNITION_MODEL', VALUES)
    })

    expect(outcome).toEqual({ ok: true })
    expect(result.current.view).not.toBe('TEXT_RECOGNITION_MODEL')
  })

  it('says the check still runs instead of reporting it as failed', async () => {
    api.getInstallation.mockResolvedValue(status({}))
    const { result } = await signedIn()
    api.checkInstallationStep.mockRejectedValue(clientTimeout())

    let outcome: Awaited<ReturnType<typeof result.current.submitStep>> | undefined
    await act(async () => {
      outcome = await result.current.submitStep('TEXT_RECOGNITION_MODEL', VALUES)
    })

    expect(outcome).toMatchObject({
      ok: false,
      failure: { message: expect.stringMatching(/OPAA prüft im Hintergrund weiter/) },
    })
    expect(result.current.view).toBe('TEXT_RECOGNITION_MODEL')
  })
})
