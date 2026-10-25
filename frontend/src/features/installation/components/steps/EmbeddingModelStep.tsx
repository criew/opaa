import { useId, useState } from 'react'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { apiKeyPart } from '../../utils/apiKey'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import ApiKeyField from '../ApiKeyField'
import FormField from '../FormField'
import StatusLine from '@/shared/ui/StatusLine'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface EmbeddingModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['EMBEDDING_MODEL']) => Promise<WizardResult>
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
 * The model that turns documents and questions into vectors. Its check is a probe embedding,
 * which also fixes the vector size the completion creates the vector table with; the model
 * cannot be switched through the interface afterwards (ADR-0060, Entscheidung 10).
 */
export default function EmbeddingModelStep({
  step,
  submit,
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

  return (
    <StepFrame
      title={STEP_INFO.EMBEDDING_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.EMBEDDING_MODEL.laterUnder}
      description="Das Modell, das Dokumente und Fragen für die Suche in Vektoren übersetzt. Die Prüfung ist eine Probeeinbettung; sie legt die Vektorgröße fest. Nach dem Abschluss lässt sich das Modell nicht mehr über die Oberfläche wechseln."
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
      <FormField
        id={`${id}-base-url`}
        label="Basis-Adresse"
        inputMode="url"
        value={baseUrl}
        onChange={setBaseUrl}
        error={form.errors.baseUrl}
        hint="Etwa http://ollama:11434/v1, so wie das Backend den Dienst erreicht."
      />
      <FormField
        id={`${id}-model`}
        label="Modell-Kennung"
        value={model}
        onChange={setModel}
        error={form.errors.model}
        hint="Der Name, unter dem der Dienst das Modell führt, etwa bge-m3."
      />
      <ApiKeyField
        id={`${id}-api-key`}
        keyStored={Boolean(stored?.apiKeySet)}
        value={apiKey}
        onChange={setApiKey}
        remove={removeKey}
        onRemoveChange={setRemoveKey}
        error={form.errors.apiKey}
      />
      <FormField
        id={`${id}-max-input-tokens`}
        label="Eingabegrenze (Token)"
        optional
        inputMode="numeric"
        value={maxInputTokens}
        onChange={setMaxInputTokens}
        error={form.errors.maxInputTokens}
        hint={`So viele Token nimmt der Dienst je Abschnitt an. Leer lassen für ${DEFAULT_MAX_INPUT_TOKENS}.`}
      />
    </StepFrame>
  )
}
