import type { InstallationState, InstallationStep } from '../api/types'

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

function initialSteps(): InstallationStep[] {
  return [
    { step: 'FIRST_ADMIN', required: true, saved: false, checked: false, values: null },
    { step: 'SIGN_IN', required: true, saved: false, checked: false, values: null },
    { step: 'CHAT_MODEL', required: true, saved: false, checked: false, values: null },
    { step: 'BRANDING', required: false, saved: false, checked: false, values: null },
  ]
}

function storedSteps(): InstallationStep[] | null {
  try {
    const raw = window.sessionStorage.getItem(STEPS_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as InstallationStep[]) : null
  } catch {
    return null
  }
}

function remember(steps: InstallationStep[]) {
  try {
    window.sessionStorage.setItem(STEPS_STORAGE_KEY, JSON.stringify(steps))
  } catch {
    // no storage: the state lasts until the next reload
  }
}

let pendingOverride: boolean | null = null
let mockSteps: InstallationStep[] = storedSteps() ?? initialSteps()

function pendingFromBrowser(): boolean {
  if (typeof window === 'undefined') return false
  try {
    const fromQuery = new URLSearchParams(window.location.search).get('mockInstallation')
    if (fromQuery === 'pending' || fromQuery === 'done') {
      window.sessionStorage.setItem(STORAGE_KEY, fromQuery)
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

export function mockInstallationState(): InstallationState {
  return { steps: mockSteps }
}

export function updateMockStep(step: InstallationStep['step'], patch: Partial<InstallationStep>) {
  mockSteps = mockSteps.map((s) => (s.step === step ? { ...s, ...patch } : s))
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

export function resetMockInstallation() {
  pendingOverride = null
  mockSteps = initialSteps()
  try {
    window.sessionStorage.removeItem(STEPS_STORAGE_KEY)
  } catch {
    // no storage, nothing to forget
  }
}
