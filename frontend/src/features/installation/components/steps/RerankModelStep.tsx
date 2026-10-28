import { useState } from 'react'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import { MODEL_ROLE_TEXTS } from '@/shared/ui/modelDiscovery/modelRoleTexts'
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
import ModelEndpointFields from '../ModelEndpointFields'
import StepFrame, { type StepNavigationProps } from '../StepFrame'

interface RerankModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['RERANK_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const RERANK_MODEL_FIELDS = ['baseUrl', 'model', 'apiKey']

/**
 * The model that orders the candidates of every search anew, with the fields and the probe of
 * the rerank model administration; the completion switches reranking on with it. The list of
 * models is offered where the endpoint has one, otherwise the identifier is typed.
 */
export default function RerankModelStep({
  step,
  submit,
  discover,
  onBack,
  onSkip,
}: RerankModelStepProps) {
  const stored = step.rerankModel ?? null
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? '')
  const [model, setModel] = useState(stored?.model ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const form = useStepForm(step.checked ? null : step.checkMessage)

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
    })
    void form.submit(localErrors, RERANK_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...apiKeyPart(apiKey, removeKey),
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.RERANK_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.RERANK_MODEL.laterUnder}
      description={`${MODEL_ROLE_TEXTS.rerank.purpose} Die Prüfung schickt eine Rerank-Anfrage mit einem Prüftext an den Endpunkt, wie „Verbindung testen“ in der Modellverwaltung; der Abschluss schaltet das Reranking damit ein. Ohne Rerank-Modell sucht OPAA ohne diese Stufe, bis die Systemverwaltung es unter Modelle einschaltet.`}
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Probe an das Rerank-Modell läuft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <ModelEndpointFields
        purpose="rerank"
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
      />
    </StepFrame>
  )
}
