import { useId, useState } from 'react'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import StatusLine from '@/shared/ui/StatusLine'
import { formatTokens } from '@/shared/ui/modelDiscovery/modelDiscovery'
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

interface EmbeddingModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['EMBEDDING_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const DEFAULT_MAX_INPUT_TOKENS = 2048
const EMBEDDING_MODEL_FIELDS = ['baseUrl', 'model', 'apiKey', 'maxInputTokens']

function maxInputTokensError(value: string): string | undefined {
  if (isBlank(value)) return undefined
  const number = Number(value)
  return Number.isInteger(number) && number > 0
    ? undefined
    : 'Die Eingabegrenze ist eine ganze Zahl größer als 0.'
}

/**
 * The model that turns documents and questions into vectors, chosen from what its endpoint offers
 * or typed by hand. Its check is a probe embedding, which also fixes the vector size the
 * completion creates the vector table with; the model cannot be switched through the interface
 * afterwards (ADR-0060, Entscheidung 10).
 */
export default function EmbeddingModelStep({
  step,
  submit,
  discover,
  onBack,
  onSkip,
}: EmbeddingModelStepProps) {
  const id = useId()
  const stored = step.embeddingModel ?? null
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? '')
  const [model, setModel] = useState(stored?.model ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const [maxInputTokens, setMaxInputTokens] = useState(
    stored?.maxInputTokens == null ? '' : String(stored.maxInputTokens),
  )
  const [contextLength, setContextLength] = useState<number | null>(null)
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const dimensions = step.checked ? stored?.dimensions : null

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
      maxInputTokens: maxInputTokensError(maxInputTokens),
    })
    void form.submit(localErrors, EMBEDDING_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...apiKeyPart(apiKey, removeKey),
        maxInputTokens: isBlank(maxInputTokens) ? null : Number(maxInputTokens),
      }),
    )
  }

  const inputHint = `So viele Token nimmt der Dienst je Abschnitt an. Leer lassen für ${DEFAULT_MAX_INPUT_TOKENS}.`

  return (
    <StepFrame
      title={STEP_INFO.EMBEDDING_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.EMBEDDING_MODEL.laterUnder}
      description="Das Modell, das Dokumente und Fragen für die Suche in Vektoren übersetzt. Die Prüfung ist eine Probeeinbettung; sie legt die Vektorgröße fest. Ohne Embedding-Modell gibt es keinen Suchindex, bis die Systemverwaltung eines unter Modelle festlegt; ein späterer Wechsel bettet alle Abschnitte neu ein."
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Probeeinbettung läuft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      {dimensions != null && (
        <StatusLine
          tone="success"
          headline={`Geprüft: ${dimensions} Dimensionen`}
          detail="Mit dieser Vektorgröße legt der Abschluss den Suchindex an."
        />
      )}
      <ModelEndpointFields
        purpose="embedding"
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
        onModelSelect={(selected) => setContextLength(selected.contextLength ?? null)}
        errors={form.errors}
        discover={discover}
      >
        <FormField
          id={`${id}-max-input-tokens`}
          label="Eingabegrenze (Token)"
          optional
          inputMode="numeric"
          value={maxInputTokens}
          onChange={setMaxInputTokens}
          error={form.errors.maxInputTokens}
          hint={
            contextLength
              ? `Der Endpunkt meldet ein Kontextfenster von ${formatTokens(contextLength)} Token; mehr nimmt das Modell nicht an. ${inputHint}`
              : inputHint
          }
        />
      </ModelEndpointFields>
    </StepFrame>
  )
}
