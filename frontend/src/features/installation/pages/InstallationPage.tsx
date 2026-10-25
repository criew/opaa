import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { LOGIN_ROUTE } from '@/shared/routes'
import type {
  InstallationStatusResponse,
  InstallationStepId,
  InstallationStepState,
  StepInput,
} from '../api/types'
import InstallationLayout from '../components/InstallationLayout'
import StepList from '../components/StepList'
import SummaryStep from '../components/SummaryStep'
import TokenStep from '../components/TokenStep'
import ChatModelStep from '../components/steps/ChatModelStep'
import EmbeddingModelStep from '../components/steps/EmbeddingModelStep'
import FirstAdminStep from '../components/steps/FirstAdminStep'
import SignInStep from '../components/steps/SignInStep'
import { useInstallationWizard, type WizardResult } from '../hooks/useInstallationWizard'
import { findStep, nextView, previousView, viewOrder, type WizardView } from '../utils/steps'

/**
 * The sign-in page is left with a full load on purpose: the application then reads its
 * configuration afresh and no longer finds an installation pending.
 */
function Outcome({ heading, message }: { heading: string; message: string }) {
  return (
    <InstallationLayout>
      <Typography component="h2" sx={{ fontSize: 22, fontWeight: 600 }}>
        {heading}
      </Typography>
      <Alert severity="success" sx={{ mt: 2 }}>
        {message}
      </Alert>
      <Button variant="contained" href={LOGIN_ROUTE} sx={{ mt: 3 }}>
        Zur Anmeldung
      </Button>
    </InstallationLayout>
  )
}

function progressOf(
  installation: InstallationStatusResponse | null,
  view: WizardView,
): string | null {
  if (!installation) return null
  const order = viewOrder(installation)
  return `Schritt ${order.indexOf(view) + 1} von ${order.length}`
}

type Submit = <S extends InstallationStepId>(
  step: S,
  values: StepInput[S] | null,
) => Promise<WizardResult>

interface StepViewProps {
  step: InstallationStepState
  submit: Submit
  onBack?: () => void
  onNext: () => void
}

/** The form of one step; an optional step can be skipped. */
function StepView({ step, submit, onBack, onNext }: StepViewProps) {
  const nav = { onBack, onSkip: step.mandatory ? undefined : onNext }
  switch (step.step) {
    case 'FIRST_ADMIN':
      return <FirstAdminStep step={step} submit={(v) => submit('FIRST_ADMIN', v)} {...nav} />
    case 'SIGN_IN':
      return <SignInStep step={step} submit={(v) => submit('SIGN_IN', v)} {...nav} />
    case 'CHAT_MODEL':
      return <ChatModelStep step={step} submit={(v) => submit('CHAT_MODEL', v)} {...nav} />
    case 'EMBEDDING_MODEL':
      return (
        <EmbeddingModelStep step={step} submit={(v) => submit('EMBEDDING_MODEL', v)} {...nav} />
      )
  }
}

/**
 * The set-up assistant of an installation in installation mode (ADR-0060). Reachable without a
 * session; every step behind the installation token. A reload asks for the token again and
 * continues at the first mandatory step still open.
 */
export default function InstallationPage() {
  const wizard = useInstallationWizard()
  const { installation, view } = wizard

  if (wizard.phase === 'completed') {
    return (
      <Outcome
        heading="Installation abgeschlossen"
        message="Die Einrichtung ist abgeschlossen. Melden Sie sich jetzt mit dem ersten Konto an."
      />
    )
  }
  if (wizard.phase === 'installed') {
    return (
      <Outcome
        heading="Bereits eingerichtet"
        message="Diese Installation ist bereits abgeschlossen; der Assistent steht nicht mehr zur Verfügung."
      />
    )
  }

  const navigation = (
    <StepList
      installation={installation}
      view={view}
      onSelect={installation ? wizard.goTo : undefined}
    />
  )

  if (wizard.phase === 'token' || !installation) {
    return (
      <InstallationLayout navigation={navigation}>
        <TokenStep submit={wizard.submitToken} notice={wizard.tokenNotice} />
      </InstallationLayout>
    )
  }

  const back = previousView(installation, view)
  const onBack = back ? () => wizard.goTo(back) : undefined
  const step = view === 'TOKEN' || view === 'SUMMARY' ? undefined : findStep(installation, view)

  return (
    <InstallationLayout progress={progressOf(installation, view)} navigation={navigation}>
      {step ? (
        <StepView
          key={step.step}
          step={step}
          submit={wizard.submitStep}
          onBack={onBack}
          onNext={() => wizard.goTo(nextView(installation, step.step))}
        />
      ) : (
        <SummaryStep installation={installation} complete={wizard.complete} onBack={onBack} />
      )}
    </InstallationLayout>
  )
}
