import { useId, useRef, useState } from 'react'
import type { WizardResult } from '../hooks/useInstallationWizard'
import { TOKEN_LABEL } from '../utils/steps'
import SecretInput from './SecretInput'
import StepFrame from './StepFrame'

interface TokenStepProps {
  submit: (token: string) => Promise<WizardResult>
  /** Why the token is asked for again, e.g. after the operator replaced it. */
  notice: string | null
}

/**
 * The door to the assistant: nothing of the installation is shown before the backend accepted
 * the token. The token stays in this tab's memory only; a reload asks for it again and resumes
 * from the stored state.
 */
export default function TokenStep({ submit, notice }: TokenStepProps) {
  const id = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const [token, setToken] = useState('')
  const [error, setError] = useState<string | undefined>()
  const [failure, setFailure] = useState<string | null>(notice)
  const [busy, setBusy] = useState(false)

  async function handleSubmit() {
    const trimmed = token.trim()
    if (trimmed === '') {
      setError('Bitte das Installations-Token eingeben.')
      setFailure(null)
      inputRef.current?.focus()
      return
    }
    setBusy(true)
    setError(undefined)
    setFailure(null)
    const result = await submit(trimmed)
    setBusy(false)
    if (result.ok) return
    if (result.failure.kind === 'token') {
      setError(result.failure.message)
      inputRef.current?.focus()
    } else {
      setFailure(result.failure.message)
    }
  }

  return (
    <StepFrame
      title={TOKEN_LABEL}
      mandatory
      description="Die Einrichtung ist durch ein einmaliges Token geschützt. Der erste Start schreibt es als markierten Block ins Log des Backends, sofern der Betrieb es nicht über OPAA_INSTALLATION_TOKEN vorgegeben hat."
      failure={failure}
      busy={busy}
      submitLabel="Token prüfen"
      busyAnnouncement="Token wird geprüft"
      focusHeading={notice !== null}
      onSubmit={() => void handleSubmit()}
    >
      <SecretInput
        id={`${id}-token`}
        label="Token"
        autoComplete="one-time-code"
        inputRef={inputRef}
        value={token}
        onChange={setToken}
        error={error}
      />
    </StepFrame>
  )
}
