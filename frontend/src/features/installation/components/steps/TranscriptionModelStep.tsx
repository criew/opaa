import { useId, useState } from 'react'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import { MODEL_ROLE_TEXTS } from '@/shared/ui/modelDiscovery/modelRoleTexts'
import {
  TRANSCRIPTION_TEXTS,
  transcriptionLanguageError,
} from '@/shared/ui/transcription/transcriptionSettings'
import type {
  InstallationModelDiscoveryRequest,
  InstallationStepState,
  StepInput,
} from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { apiKeyPart } from '../../utils/apiKey'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import FormField from '../FormField'
import ModelEndpointFields from '../ModelEndpointFields'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface TranscriptionModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['TRANSCRIPTION_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const TRANSCRIPTION_MODEL_FIELDS = ['baseUrl', 'model', 'apiKey', 'language']
const DEFAULT_LANGUAGE = 'de'

/**
 * The model that turns dictated questions into text, with the texts and the language rule of
 * „Modelle → Spracheingabe“. Its check is the probe of the model administration, which sends no
 * recording; the completion switches speech input on with it. The API lists the step only while
 * the operation allows speech input.
 */
export default function TranscriptionModelStep({
  step,
  submit,
  discover,
  onBack,
  onSkip,
}: TranscriptionModelStepProps) {
  const id = useId()
  const stored = step.transcriptionModel ?? null
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? '')
  const [model, setModel] = useState(stored?.model ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const [language, setLanguage] = useState(stored ? stored.language : DEFAULT_LANGUAGE)
  const form = useStepForm(step.checked ? null : step.checkMessage)

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
      language: transcriptionLanguageError(language),
    })
    void form.submit(localErrors, TRANSCRIPTION_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...apiKeyPart(apiKey, removeKey),
        language: language.trim(),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.TRANSCRIPTION_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.TRANSCRIPTION_MODEL.laterUnder}
      description={`${MODEL_ROLE_TEXTS.transcription.purpose} Die Prüfung fragt den Endpunkt nach seinen Modellen und sendet keine Aufnahme. Ohne Transkriptionsmodell bleibt die Spracheingabe aus, bis die Systemverwaltung eines unter Modelle → Spracheingabe einträgt.`}
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Endpunkt wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <ModelEndpointFields
        purpose="transcription"
        stored={stored}
        baseUrl={baseUrl}
        onBaseUrlChange={setBaseUrl}
        apiKey={apiKey}
        onApiKeyChange={setApiKey}
        removeKey={removeKey}
        onRemoveKeyChange={setRemoveKey}
        model={model}
        onModelChange={setModel}
        errors={form.errors}
        discover={discover}
      >
        <FormField
          id={`${id}-language`}
          label={TRANSCRIPTION_TEXTS.languageLabel}
          optional
          value={language}
          onChange={setLanguage}
          error={form.errors.language}
          hint={TRANSCRIPTION_TEXTS.languageHint}
          maxLength={8}
        />
      </ModelEndpointFields>
    </StepFrame>
  )
}
