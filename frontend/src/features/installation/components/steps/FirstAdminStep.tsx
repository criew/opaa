import { useId, useState } from 'react'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, emailError, isBlank } from '../../utils/validation'
import FormField from '../FormField'
import SecretInput from '../SecretInput'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

const FIRST_ADMIN_FIELDS = ['email', 'displayName', 'password', 'passwordRepeat']

interface FirstAdminStepProps extends StepNavigationProps {
  step: InstallationStepState
  /** `null` re-checks the stored account without saving it again. */
  submit: (values: StepInput['FIRST_ADMIN'] | null) => Promise<WizardResult>
}

/**
 * The first account: a local system administrator, the emergency account of ADR-0033. The API
 * takes the password with every save; a stored account left unchanged is therefore only checked
 * again, and changing it asks for the password anew. The password rule is the backend's; its
 * field errors arrive per rule and stand under the field.
 */
export default function FirstAdminStep({ step, submit, onBack, onSkip }: FirstAdminStepProps) {
  const id = useId()
  const stored = step.firstAdmin ?? null
  const [email, setEmail] = useState(stored?.email ?? '')
  const [displayName, setDisplayName] = useState(stored?.displayName ?? '')
  const [password, setPassword] = useState('')
  const [passwordRepeat, setPasswordRepeat] = useState('')
  const passwordStored = Boolean(stored?.passwordSet)
  const form = useStepForm(step.checked ? null : step.checkMessage)

  function handleSubmit() {
    const unchanged =
      passwordStored &&
      password === '' &&
      email.trim() === stored?.email &&
      displayName.trim() === stored?.displayName
    let passwordMessage: string | undefined
    if (!unchanged && password === '') {
      passwordMessage = passwordStored
        ? 'Zum Ändern des Kontos bitte das Passwort erneut eingeben.'
        : 'Bitte ein Passwort angeben.'
    }
    const localErrors = compact({
      email: emailError(email),
      displayName: isBlank(displayName) ? 'Bitte einen Anzeigenamen angeben.' : undefined,
      password: passwordMessage,
      passwordRepeat:
        password !== '' && password !== passwordRepeat
          ? 'Die beiden Eingaben stimmen nicht überein.'
          : undefined,
    })
    void form.submit(localErrors, FIRST_ADMIN_FIELDS, () =>
      submit(unchanged ? null : { email: email.trim(), displayName: displayName.trim(), password }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.FIRST_ADMIN.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.FIRST_ADMIN.laterUnder}
      description="Das erste Konto ist ein lokales Konto der Systemverwaltung. Mit ihm melden Sie sich nach dem Abschluss an; es bleibt der Zugang, falls ein Identitätsanbieter später ausfällt."
      failure={form.failure}
      busy={form.busy}
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <FormField
        id={`${id}-email`}
        label="E-Mail-Adresse"
        type="email"
        inputMode="email"
        autoComplete="username"
        value={email}
        onChange={setEmail}
        error={form.errors.email}
        hint="Eine zustellbare Adresse; über sie läuft auch das Zurücksetzen des Passworts."
      />
      <FormField
        id={`${id}-display-name`}
        label="Anzeigename"
        autoComplete="name"
        value={displayName}
        onChange={setDisplayName}
        error={form.errors.displayName}
      />
      <SecretInput
        id={`${id}-password`}
        label="Passwort"
        autoComplete="new-password"
        value={password}
        onChange={setPassword}
        stored={passwordStored}
        error={form.errors.password}
        hint="Es gilt die Passwortregel der lokalen Konten."
      />
      {(!passwordStored || password !== '') && (
        <SecretInput
          id={`${id}-password-repeat`}
          label="Passwort wiederholen"
          autoComplete="new-password"
          value={passwordRepeat}
          onChange={setPasswordRepeat}
          error={form.errors.passwordRepeat}
        />
      )}
    </StepFrame>
  )
}
