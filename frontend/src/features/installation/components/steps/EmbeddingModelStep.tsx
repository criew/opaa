import { useId, useState } from 'react'
import { secretPart, maxInputTokensError } from '@/shared/ui/setup/setupDrafts'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import StatusLine from '@/shared/ui/StatusLine'
import ModelEndpointFields from '@/shared/ui/setup/ModelEndpointFields'
import { EmbeddingModelFields } from '@/shared/ui/setup/ModelParameterFields'
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

interface EmbeddingModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['EMBEDDING_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const EMBEDDING_MODEL_FIELDS = ['baseUrl', 'model', 'apiKey', 'maxInputTokens']

/**
 * The model that turns documents and questions into vectors, with the fields of „Modelle →
 * Einbettung“. Its check is a probe embedding, which also fixes the vector size the completion
 * creates the vector table with; a later change embeds everything anew (ADR-0060, Entscheidung 10).
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
    const key = secretPart(apiKey, removeKey)
    void form.submit(localErrors, EMBEDDING_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...(key === undefined ? {} : { apiKey: key }),
        maxInputTokens: isBlank(maxInputTokens) ? null : Number(maxInputTokens),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.EMBEDDING_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.EMBEDDING_MODEL.laterUnder}
      description="Das Modell, das Dokumente und Fragen für die Suche in Vektoren übersetzt; die Prüfung legt die Vektorgröße fest. Übersprungen gibt es keinen Suchindex, bis die Systemverwaltung ein Modell unter „Modelle → Einbettung“ festlegt."
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
        <EmbeddingModelFields
          idPrefix={id}
          maxInputTokens={maxInputTokens}
          onMaxInputTokensChange={setMaxInputTokens}
          contextLength={contextLength}
          errors={form.errors}
        />
      </ModelEndpointFields>
    </StepFrame>
  )
}
