import { useId, useState } from 'react'
import {
  secretPart,
  maxTokensError,
  parseTemperature,
  temperatureError,
} from '@/shared/ui/setup/setupDrafts'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import { DEFAULT_MAX_TOKENS, suggestedMaxTokens } from '@/shared/ui/modelDiscovery/modelDiscovery'
import ModelEndpointFields from '@/shared/ui/setup/ModelEndpointFields'
import { ChatModelFields } from '@/shared/ui/setup/ModelParameterFields'
import type {
  InstallationModelDiscoveryRequest,
  InstallationStepState,
  StepInput,
} from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface ChatModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['CHAT_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const CHAT_MODEL_FIELDS = ['baseUrl', 'model', 'apiKey', 'temperature', 'maxTokens']

/**
 * The model that answers in the chat, with the fields of „Modelle → Chat-Modell“. Its check is
 * the connection test of the model administration; a stored key is never shown again.
 */
export default function ChatModelStep({
  step,
  submit,
  discover,
  onBack,
  onSkip,
}: ChatModelStepProps) {
  const id = useId()
  const stored = step.chatModel ?? null
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? '')
  const [model, setModel] = useState(stored?.model ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const [temperature, setTemperature] = useState(
    stored?.temperature == null ? '' : String(stored.temperature),
  )
  const [maxTokens, setMaxTokens] = useState(String(stored?.maxTokens ?? DEFAULT_MAX_TOKENS))
  // A choice from the list suggests the answer length as long as nobody set it by hand.
  const [maxTokensTouched, setMaxTokensTouched] = useState(stored !== null)
  const [contextLength, setContextLength] = useState<number | null>(null)
  const form = useStepForm(step.checked ? null : step.checkMessage)

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
      temperature: temperatureError(temperature),
      maxTokens: maxTokensError(maxTokens),
    })
    const key = secretPart(apiKey, removeKey)
    void form.submit(localErrors, CHAT_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...(key === undefined ? {} : { apiKey: key }),
        temperature: parseTemperature(temperature),
        maxTokens: Number(maxTokens),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.CHAT_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.CHAT_MODEL.laterUnder}
      description="Das Modell, das im Chat antwortet, angesprochen über eine OpenAI-kompatible Schnittstelle. Übersprungen beantwortet der Chat keine Fragen, bis die Systemverwaltung eines unter „Modelle“ anlegt."
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Verbindung zum Modell wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <ModelEndpointFields
        purpose="chat"
        stored={stored}
        baseUrl={baseUrl}
        onBaseUrlChange={setBaseUrl}
        apiKey={apiKey}
        onApiKeyChange={setApiKey}
        removeKey={removeKey}
        onRemoveKeyChange={setRemoveKey}
        model={model}
        onModelChange={(identifier) => {
          setModel(identifier)
          setContextLength(null)
        }}
        onModelSelect={(selected) => {
          setContextLength(selected.contextLength ?? null)
          if (!maxTokensTouched && selected.contextLength) {
            setMaxTokens(String(suggestedMaxTokens(selected.contextLength)))
          }
        }}
        errors={form.errors}
        discover={discover}
      >
        <ChatModelFields
          idPrefix={id}
          temperature={temperature}
          onTemperatureChange={setTemperature}
          maxTokens={maxTokens}
          onMaxTokensChange={(value) => {
            setMaxTokensTouched(true)
            setMaxTokens(value)
          }}
          contextLength={contextLength}
          errors={form.errors}
        />
      </ModelEndpointFields>
    </StepFrame>
  )
}
