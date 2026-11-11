import { useState } from 'react'
import {
  secretPart,
  isVisionDraft,
  textRecognitionDraftOf,
  type TextRecognitionDraft,
} from '@/shared/ui/setup/setupDrafts'
import TextRecognitionFields from '@/shared/ui/setup/TextRecognitionFields'
import { TEXT_RECOGNITION_PURPOSE } from '@/shared/ui/textRecognition/textRecognitionTexts'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface TextRecognitionModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['TEXT_RECOGNITION_MODEL']) => Promise<WizardResult>
}

const TEXT_RECOGNITION_FIELDS = ['connectionType', 'baseUrl', 'model', 'apiKey']

/**
 * The text recognition (OCR) of scanned PDFs, with the fields of „Modelle → Texterkennung“. The
 * check is the staged test of the administration with its test page. The API lists the step only
 * while the operation releases text recognition.
 */
export default function TextRecognitionModelStep({
  step,
  submit,
  onBack,
  onSkip,
}: TextRecognitionModelStepProps) {
  const stored = step.textRecognitionModel ?? null
  const [draft, setDraft] = useState<TextRecognitionDraft>(() => textRecognitionDraftOf(stored))
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const isVision = isVisionDraft(draft)

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(draft.baseUrl, 'die Basis-Adresse'),
      model: isVision && isBlank(draft.model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
    })
    const key = secretPart(draft.apiKey, draft.removeKey)
    void form.submit(localErrors, TEXT_RECOGNITION_FIELDS, () =>
      submit({
        connectionType: draft.connectionType,
        baseUrl: draft.baseUrl.trim(),
        model: isVision ? draft.model.trim() : null,
        ...(key === undefined ? {} : { apiKey: key }),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.TEXT_RECOGNITION_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.TEXT_RECOGNITION_MODEL.laterUnder}
      description={`${TEXT_RECOGNITION_PURPOSE} Übersprungen bleiben solche Scans unauffindbar, bis die Systemverwaltung sie unter „Modelle → Texterkennung“ einrichtet.`}
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Dienst wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <TextRecognitionFields
        value={draft}
        onChange={(patch) => setDraft((current) => ({ ...current, ...patch }))}
        apiKeyStored={Boolean(stored?.apiKeySet)}
        errors={form.errors}
      />
    </StepFrame>
  )
}
