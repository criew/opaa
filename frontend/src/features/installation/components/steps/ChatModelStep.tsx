import { useId, useState } from 'react'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import { formatTokens, suggestedMaxTokens } from '@/shared/ui/modelDiscovery/modelDiscovery'
import type {
  InstallationModelDiscoveryRequest,
  InstallationStepState,
  StepInput,
} from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import FormField from '../FormField'
import ModelEndpointFields from '../ModelEndpointFields'
import { apiKeyPart } from '../../utils/apiKey'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface ChatModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['CHAT_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const DEFAULT_MAX_TOKENS = '8000'
const CHAT_MODEL_FIELDS = ['baseUrl', 'model', 'apiKey', 'temperature', 'maxTokens']
const MAX_TEMPERATURE = 2

function temperatureError(value: string): string | undefined {
  if (isBlank(value)) return undefined
  const number = Number(value.replace(',', '.'))
  return Number.isFinite(number) && number >= 0 && number <= MAX_TEMPERATURE
    ? undefined
    : `Die Temperatur ist eine Zahl von 0 bis ${MAX_TEMPERATURE}. Für keine Temperatur das Feld leeren.`
}

function maxTokensError(value: string): string | undefined {
  if (isBlank(value)) return undefined
  const number = Number(value)
  return Number.isInteger(number) && number > 0
    ? undefined
    : 'Die maximale Antwortlänge ist eine ganze Zahl größer als 0.'
}

/**
 * The model that answers in the chat, over an OpenAI-compatible interface, chosen from what its
 * endpoint offers or typed by hand. Its check is the connection test of the model
 * administration; a stored key is never shown again.
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
  const [maxTokens, setMaxTokens] = useState(
    stored?.maxTokens == null ? DEFAULT_MAX_TOKENS : String(stored.maxTokens),
  )
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
    void form.submit(localErrors, CHAT_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...apiKeyPart(apiKey, removeKey),
        temperature: isBlank(temperature) ? null : Number(temperature.replace(',', '.')),
        maxTokens: isBlank(maxTokens) ? null : Number(maxTokens),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.CHAT_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.CHAT_MODEL.laterUnder}
      description="Das Modell, das im Chat antwortet. OPAA spricht es über eine OpenAI-kompatible Schnittstelle an, etwa Ollama, vLLM oder einen Cloud-Anbieter. Die Prüfung ist ein Verbindungstest."
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
        <FormField
          id={`${id}-temperature`}
          label="Temperatur"
          optional
          inputMode="decimal"
          value={temperature}
          onChange={setTemperature}
          error={form.errors.temperature}
          hint="Leer lassen, um die Vorgabe des Modells zu nutzen."
        />
        <FormField
          id={`${id}-max-tokens`}
          label="Maximale Antwortlänge (Token)"
          optional
          inputMode="numeric"
          value={maxTokens}
          onChange={(value) => {
            setMaxTokensTouched(true)
            setMaxTokens(value)
          }}
          error={form.errors.maxTokens}
          hint={
            contextLength
              ? `Kontextfenster des Modells: ${formatTokens(contextLength)} Token. Vorgeschlagen ist höchstens die Hälfte davon.`
              : `Leer lassen für ${DEFAULT_MAX_TOKENS} Token.`
          }
        />
      </ModelEndpointFields>
    </StepFrame>
  )
}
