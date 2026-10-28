import type { DiscoveredLlmModel } from '@/shared/api/types'
import type { InstallationStatusResponse, InstallationStepState } from '../api/types'

/**
 * The mock's installation: installed by default, so every other mock keeps working. Pending
 * only where a test asks for it or the browser tab was opened with `?mockInstallation=pending`
 * (remembered in the tab's sessionStorage, `?mockInstallation=done` returns to installed). The
 * steps are kept there too, so a reload resumes at the stored state as against the backend.
 */
export const MOCK_INSTALLATION_TOKEN = 'opaa-installation-demo-0000000001'

/** Addresses containing this word fail the step's check, so the failure path is reachable. */
export const MOCK_UNREACHABLE_MARKER = 'kaputt'

const STORAGE_KEY = 'opaa.mockInstallation'
const STEPS_STORAGE_KEY = 'opaa.mockInstallationSteps'

/** The vector size the mock's probe embedding reports. */
export const MOCK_EMBEDDING_DIMENSIONS = 1024

const MAIL_STEP: InstallationStepState = {
  step: 'MAIL',
  mandatory: false,
  saved: false,
  checked: false,
}

const RERANK_STEP: InstallationStepState = {
  step: 'RERANK_MODEL',
  mandatory: false,
  saved: false,
  checked: false,
}

const TRANSCRIPTION_STEP: InstallationStepState = {
  step: 'TRANSCRIPTION_MODEL',
  mandatory: false,
  saved: false,
  checked: false,
}

/** What a rerank endpoint with a model list offers; the reranker first, a stray model after. */
export const mockRerankDiscoveredModels: DiscoveredLlmModel[] = [
  { identifier: 'BAAI/bge-reranker-v2-m3', capabilities: [], chatSuitable: false },
  { identifier: 'BAAI/bge-m3', capabilities: [], chatSuitable: false },
]

/** What a transcription endpoint lists in the mock: two speech models and a chat model. */
export const mockTranscriptionDiscoveredModels: DiscoveredLlmModel[] = [
  { identifier: 'whisper-1', capabilities: [], chatSuitable: false },
  { identifier: 'gpt-4o-transcribe', capabilities: [], chatSuitable: false },
  { identifier: 'gpt-4.1', capabilities: [], chatSuitable: true },
]

const BRANDING_STEP: InstallationStepState = {
  step: 'BRANDING',
  mandatory: false,
  saved: false,
  checked: false,
}

/**
 * The mock's steps. With `withEmbedding` the full list of the API, embedding, rerank and
 * transcription model and branding included, as `?mockInstallation=embedding` and {@link
 * withMockEmbeddingStep} set it up; without, the shorter list most tests are written against, to
 * which {@link withMockBrandingStep}, {@link withMockRerankStep} and {@link
 * withMockTranscriptionStep} add their steps.
 */
function initialSteps(withEmbedding = false): InstallationStepState[] {
  const steps: InstallationStepState[] = [
    { step: 'FIRST_ADMIN', mandatory: true, saved: false, checked: false },
    { step: 'SIGN_IN', mandatory: true, saved: false, checked: false },
    { step: 'CHAT_MODEL', mandatory: false, saved: false, checked: false },
  ]
  return withEmbedding
    ? [
        ...steps,
        { step: 'EMBEDDING_MODEL', mandatory: false, saved: false, checked: false },
        RERANK_STEP,
        TRANSCRIPTION_STEP,
        MAIL_STEP,
        BRANDING_STEP,
      ]
    : [...steps, MAIL_STEP]
}

let publicBaseUrlConfigured = true

/** Whether the mock reports `OPAA_PUBLIC_BASE_URL` as set; true unless a test says otherwise. */
export function setMockPublicBaseUrlConfigured(configured: boolean) {
  publicBaseUrlConfigured = configured
}

/** One check the mock answered with a test recipient, as the assistant sent it. */
let testRecipients: string[] = []

export function recordMockTestRecipient(recipient: string) {
  testRecipients = [...testRecipients, recipient]
}

/** The test recipients since the last reset, oldest first. */
export function mockTestRecipients(): readonly string[] {
  return testRecipients
}

let skippedSteps: string[] = []

export function recordMockSkip(step: string) {
  skippedSteps = [...skippedSteps, step]
}

/** The steps skipped through the API since the last reset, oldest first. */
export function mockSkippedSteps(): readonly string[] {
  return skippedSteps
}

/** The full list of the API, embedding model and branding step included (`?mockInstallation=embedding`). */
export function withMockEmbeddingStep() {
  pendingOverride = true
  mockSteps = initialSteps(true)
  remember(mockSteps)
}

/** Adds the rerank step before the mail step, as the API lists it after the model steps. */
export function withMockRerankStep() {
  pendingOverride = true
  if (!mockSteps.some((s) => s.step === 'RERANK_MODEL')) {
    const mail = mockSteps.findIndex((s) => s.step === 'MAIL')
    mockSteps = [...mockSteps.slice(0, mail), RERANK_STEP, ...mockSteps.slice(mail)]
  }
  remember(mockSteps)
}

/** Adds the branding step after the mail step, as the API lists it. */
export function withMockBrandingStep() {
  pendingOverride = true
  if (!mockSteps.some((s) => s.step === 'BRANDING')) mockSteps = [...mockSteps, BRANDING_STEP]
  remember(mockSteps)
}

/** Adds the transcription model step before the mail step, as the API lists it with speech input on. */
export function withMockTranscriptionStep() {
  pendingOverride = true
  if (!mockSteps.some((s) => s.step === 'TRANSCRIPTION_MODEL')) {
    const mail = mockSteps.findIndex((s) => s.step === 'MAIL')
    mockSteps = [...mockSteps.slice(0, mail), TRANSCRIPTION_STEP, ...mockSteps.slice(mail)]
  }
  remember(mockSteps)
}

function storedSteps(): InstallationStepState[] | null {
  try {
    const raw = window.sessionStorage.getItem(STEPS_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as InstallationStepState[]) : null
  } catch {
    return null
  }
}

function remember(steps: InstallationStepState[]) {
  try {
    window.sessionStorage.setItem(STEPS_STORAGE_KEY, JSON.stringify(steps))
  } catch {
    // no storage: the state lasts until the next reload
  }
}

let pendingOverride: boolean | null = null
let mockSteps: InstallationStepState[] = storedSteps() ?? initialSteps()

function pendingFromBrowser(): boolean {
  if (typeof window === 'undefined') return false
  try {
    const fromQuery = new URLSearchParams(window.location.search).get('mockInstallation')
    if (fromQuery === 'embedding' && !storedSteps()?.some((s) => s.step === 'EMBEDDING_MODEL')) {
      mockSteps = initialSteps(true)
      remember(mockSteps)
    }
    if (fromQuery === 'pending' || fromQuery === 'done' || fromQuery === 'embedding') {
      window.sessionStorage.setItem(STORAGE_KEY, fromQuery === 'done' ? 'done' : 'pending')
    }
    return window.sessionStorage.getItem(STORAGE_KEY) === 'pending'
  } catch {
    return false
  }
}

export function isMockInstallationPending(): boolean {
  return pendingOverride ?? pendingFromBrowser()
}

/** Puts the mock into installation mode (or out of it) for the current test. */
export function setMockInstallationPending(pending: boolean) {
  pendingOverride = pending
}

export function mockInstallationState(): InstallationStatusResponse {
  return {
    pending: true,
    completable: mockSteps.every((s) => s.checked || (!s.mandatory && !s.saved)),
    publicBaseUrlConfigured,
    steps: mockSteps,
  }
}

export function updateMockStep(
  step: InstallationStepState['step'],
  patch: Partial<InstallationStepState>,
) {
  mockSteps = mockSteps.map((s) => (s.step === step ? { ...s, ...patch } : s))
  remember(mockSteps)
}

/** Drops a step from the list, as an API that does not know it. */
export function withoutMockStep(step: InstallationStepState['step']) {
  mockSteps = mockSteps.filter((s) => s.step !== step)
  remember(mockSteps)
}

/** Marks the installation complete: the API answers 404 from now on. */
export function completeMockInstallation() {
  pendingOverride = false
  try {
    window.sessionStorage.setItem(STORAGE_KEY, 'done')
  } catch {
    // no storage, nothing to remember
  }
}

/** One model discovery the mock answered, as the assistant sent it. */
export interface MockDiscoveryRequest {
  step: string
  baseUrl: string
  apiKey: string | null | undefined
}

let discoveryRequests: MockDiscoveryRequest[] = []

/** Addresses containing this word answer without a model list. */
export const MOCK_NO_MODEL_LIST_MARKER = 'ohne-liste'

export function recordMockDiscovery(request: MockDiscoveryRequest) {
  discoveryRequests = [...discoveryRequests, request]
}

/** The model discoveries since the last reset, oldest first. */
export function mockDiscoveryRequests(): readonly MockDiscoveryRequest[] {
  return discoveryRequests
}

export function resetMockInstallation() {
  pendingOverride = null
  discoveryRequests = []
  testRecipients = []
  skippedSteps = []
  publicBaseUrlConfigured = true
  mockSteps = initialSteps()
  try {
    window.sessionStorage.removeItem(STEPS_STORAGE_KEY)
  } catch {
    // no storage, nothing to forget
  }
}
