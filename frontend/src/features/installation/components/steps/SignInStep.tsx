import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import type { InstallationStep, SignInMode, StepInput, StoredStepValues } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import FormField from '../FormField'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface SignInStepProps extends StepNavigationProps {
  step: InstallationStep
  submit: (values: StepInput['SIGN_IN']) => Promise<WizardResult>
}

const MODES: { value: SignInMode; label: string; detail: string }[] = [
  {
    value: 'LOCAL_ONLY',
    label: 'Nur lokale Konten',
    detail: 'Konten legt die Systemverwaltung in OPAA an. Ein Anbieter lässt sich später ergänzen.',
  },
  {
    value: 'LOCAL_AND_OIDC',
    label: 'Lokale Konten und ein OIDC-Anbieter',
    detail: 'Die Anmeldung läuft zusätzlich über einen Identitätsanbieter wie Keycloak.',
  },
]

/**
 * How people sign in. With a provider, its check reaches discovery and JWK set from the backend;
 * the address check of the backend refuses targets it does not allow.
 */
export default function SignInStep({ step, submit, onBack, onSkip }: SignInStepProps) {
  const id = useId()
  const stored = step.values as StoredStepValues['SIGN_IN'] | null
  const [mode, setMode] = useState<SignInMode>(stored?.mode ?? 'LOCAL_ONLY')
  const [issuerUri, setIssuerUri] = useState(stored?.oidc?.issuerUri ?? '')
  const [clientId, setClientId] = useState(stored?.oidc?.clientId ?? '')
  const [jwkSetUri, setJwkSetUri] = useState(stored?.oidc?.jwkSetUri ?? '')
  const form = useStepForm()
  const withProvider = mode === 'LOCAL_AND_OIDC'
  const legendId = `${id}-mode`

  function handleSubmit() {
    const localErrors = withProvider
      ? compact({
          'oidc.issuerUri': urlError(issuerUri, 'die Issuer-URI'),
          'oidc.clientId': isBlank(clientId) ? 'Bitte die Client-ID angeben.' : undefined,
          'oidc.jwkSetUri': urlError(jwkSetUri, 'die JWK-Set-Adresse', true),
        })
      : {}
    void form.submit(localErrors, () =>
      submit({
        mode,
        oidc: withProvider
          ? {
              issuerUri: issuerUri.trim(),
              clientId: clientId.trim(),
              jwkSetUri: isBlank(jwkSetUri) ? null : jwkSetUri.trim(),
            }
          : null,
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.SIGN_IN.label}
      required={step.required}
      laterUnder={STEP_INFO.SIGN_IN.laterUnder}
      description="Legen Sie fest, wie sich Personen anmelden. Das erste Konto meldet sich immer lokal an."
      failure={form.failure}
      busy={form.busy}
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <Box>
        <Typography id={legendId} sx={{ fontSize: 12, color: 'text.secondary', mb: '5px' }}>
          Anmeldeverfahren
        </Typography>
        <RadioGroup
          value={mode}
          onChange={(event) => setMode(event.target.value as SignInMode)}
          aria-labelledby={legendId}
        >
          {MODES.map((option) => (
            <FormControlLabel
              key={option.value}
              value={option.value}
              control={<Radio size="small" />}
              sx={{ alignItems: 'flex-start', mb: 1, '& .MuiRadio-root': { mt: -0.5 } }}
              label={
                <Box>
                  <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{option.label}</Typography>
                  <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                    {option.detail}
                  </Typography>
                </Box>
              }
            />
          ))}
        </RadioGroup>
      </Box>
      {withProvider && (
        <>
          <FormField
            id={`${id}-issuer`}
            label="Issuer-URI"
            inputMode="url"
            value={issuerUri}
            onChange={setIssuerUri}
            error={form.errors['oidc.issuerUri']}
            hint="Die Adresse, die der Anbieter in seine Tokens schreibt, bei Keycloak die Realm-Adresse."
          />
          <FormField
            id={`${id}-client`}
            label="Client-ID"
            value={clientId}
            onChange={setClientId}
            error={form.errors['oidc.clientId']}
            hint="Der beim Anbieter angelegte öffentliche Client, ohne Secret und mit PKCE."
          />
          <FormField
            id={`${id}-jwk`}
            label="JWK-Set-Adresse"
            optional
            inputMode="url"
            value={jwkSetUri}
            onChange={setJwkSetUri}
            error={form.errors['oidc.jwkSetUri']}
            hint="Nur nötig, wenn das Backend den Anbieter unter einer anderen Adresse erreicht als der Browser."
          />
        </>
      )}
    </StepFrame>
  )
}
