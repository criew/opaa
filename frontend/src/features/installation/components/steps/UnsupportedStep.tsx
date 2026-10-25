import type { InstallationStep } from '../../api/types'
import { STEP_INFO } from '../../utils/steps'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface UnsupportedStepProps extends StepNavigationProps {
  step: InstallationStep
}

/**
 * A step the backend lists but this assistant has no form for yet. An optional one can be
 * skipped; a required one keeps the installation open until the answer file covers it.
 */
export default function UnsupportedStep({ step, onBack, onSkip }: UnsupportedStepProps) {
  const info = STEP_INFO[step.step]
  return (
    <StepFrame
      title={info.label}
      required={step.required}
      laterUnder={info.laterUnder}
      description={
        step.required
          ? 'Diesen Schritt bietet der Assistent im Browser noch nicht an. Richten Sie die Installation mit einer Antwortdatei ein.'
          : 'Diesen Schritt bietet der Assistent im Browser noch nicht an. Sie können ihn überspringen und die Angaben nach dem Abschluss in der Verwaltung ergänzen.'
      }
      submitLabel="Weiter"
      onSubmit={() => onSkip?.()}
      onBack={onBack}
    >
      {null}
    </StepFrame>
  )
}
