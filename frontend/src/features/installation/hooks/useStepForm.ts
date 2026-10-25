import { useState } from 'react'
import type { WizardResult } from './useInstallationWizard'
import { byField, type FieldErrors } from '../utils/validation'

/**
 * Submission state of one step's form. Entries that fail the local checks are not sent; the
 * backend's field errors replace the local ones after a send. In both cases the focus moves to
 * the first invalid field, so the correction can start where the message stands. A step opened
 * after a failed check starts with that check's sentence.
 */
export function useStepForm(initialFailure?: string | null) {
  const [errors, setErrors] = useState<FieldErrors>({})
  const [failure, setFailure] = useState<string | null>(initialFailure ?? null)
  const [busy, setBusy] = useState(false)

  function focusFirstInvalid() {
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>('form [aria-invalid="true"]')?.focus()
    })
  }

  async function submit(localErrors: FieldErrors, send: () => Promise<WizardResult>) {
    setErrors(localErrors)
    if (Object.keys(localErrors).length > 0) {
      setFailure(null)
      focusFirstInvalid()
      return
    }
    setBusy(true)
    setFailure(null)
    const result = await send()
    setBusy(false)
    if (result.ok) return
    const fieldErrors = byField(result.failure.fieldErrors)
    setErrors(fieldErrors)
    setFailure(result.failure.message || null)
    if (Object.keys(fieldErrors).length > 0) focusFirstInvalid()
  }

  return { errors, failure, busy, submit }
}
