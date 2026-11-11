import { useId, useState } from 'react'
import OptionGroup, { type SetupOption } from '@/shared/ui/setup/OptionGroup'
import SetupField from '@/shared/ui/setup/SetupField'
import type { InstallationSignInMode, InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

const SIGN_IN_FIELDS = ['oidc.issuerUri', 'oidc.clientId', 'oidc.jwkSetUri', 'oidc.displayName']

interface SignInStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['SIGN_IN']) => Promise<WizardResult>
}

const MODES: SetupOption<InstallationSignInMode>[] = [
  {
    value: 'LOCAL_ONLY',
    label: 'Nur lokale Konten',
    hint: 'Konten legt die Systemverwaltung in OPAA an. Ein Anbieter lässt sich später ergänzen.',
  },
  {
    value: 'LOCAL_AND_OIDC',
    label: 'Lokale Konten und ein Identitätsanbieter',
    hint: 'Die Anmeldung läuft zusätzlich über einen Anbieter mit OpenID Connect, etwa Keycloak.',
  },
]

/**
 * How people sign in. With a provider, its check reaches discovery and JWK set from the backend;
 * the address check of the backend refuses targets it does not allow.
 */
export default function SignInStep({ step, submit, onBack, onSkip }: SignInStepProps) {
  const id = useId()
  const stored = step.signIn ?? null
  const [mode, setMode] = useState<InstallationSignInMode>(stored?.mode ?? 'LOCAL_ONLY')
  const [issuerUri, setIssuerUri] = useState(stored?.oidc?.issuerUri ?? '')
  const [clientId, setClientId] = useState(stored?.oidc?.clientId ?? '')
  const [jwkSetUri, setJwkSetUri] = useState(stored?.oidc?.jwkSetUri ?? '')
  const [providerName, setProviderName] = useState(stored?.oidc?.displayName ?? '')
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const withProvider = mode === 'LOCAL_AND_OIDC'

  function handleSubmit() {
    const localErrors = withProvider
      ? compact({
          'oidc.issuerUri': urlError(issuerUri, 'die Issuer-URI'),
          'oidc.clientId': isBlank(clientId) ? 'Bitte die Client-ID angeben.' : undefined,
          'oidc.jwkSetUri': urlError(jwkSetUri, 'die JWK-Set-Adresse', true),
        })
      : {}
    void form.submit(localErrors, SIGN_IN_FIELDS, () =>
      submit({
        mode,
        oidc: withProvider
          ? {
              issuerUri: issuerUri.trim(),
              clientId: clientId.trim(),
              jwkSetUri: isBlank(jwkSetUri) ? null : jwkSetUri.trim(),
              displayName: isBlank(providerName) ? null : providerName.trim(),
            }
          : undefined,
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.SIGN_IN.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.SIGN_IN.laterUnder}
      description="Legen Sie fest, ob sich Personen nur mit lokalen Konten oder zusätzlich über einen Identitätsanbieter anmelden. Das erste Konto meldet sich immer lokal an."
      failure={form.failure}
      busy={form.busy}
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <OptionGroup<InstallationSignInMode>
        label="Anmeldung"
        options={MODES}
        value={mode}
        onChange={setMode}
      />
      {withProvider && (
        <>
          <SetupField
            id={`${id}-issuer`}
            label="Issuer-URI"
            inputMode="url"
            value={issuerUri}
            onChange={setIssuerUri}
            error={form.errors['oidc.issuerUri']}
            hint="Die Adresse, die der Anbieter in seine Tokens schreibt, bei Keycloak die Realm-Adresse."
          />
          <SetupField
            id={`${id}-client`}
            label="Client-ID"
            value={clientId}
            onChange={setClientId}
            error={form.errors['oidc.clientId']}
            hint="Der beim Anbieter angelegte öffentliche Client, ohne Secret und mit PKCE."
          />
          <SetupField
            id={`${id}-jwk`}
            label="JWK-Set-Adresse"
            optional
            inputMode="url"
            value={jwkSetUri}
            onChange={setJwkSetUri}
            error={form.errors['oidc.jwkSetUri']}
            hint="Nur nötig, wenn das Backend den Anbieter unter einer anderen Adresse erreicht als der Browser."
          />
          <SetupField
            id={`${id}-provider-name`}
            label="Name auf der Anmeldeseite"
            optional
            value={providerName}
            onChange={setProviderName}
            placeholder="Verzeichnisdienst"
            error={form.errors['oidc.displayName']}
          />
        </>
      )}
    </StepFrame>
  )
}
