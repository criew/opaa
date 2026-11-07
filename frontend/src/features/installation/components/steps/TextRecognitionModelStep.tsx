import { useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import DocumentScannerOutlinedIcon from '@mui/icons-material/DocumentScannerOutlined'
import ImageSearchOutlinedIcon from '@mui/icons-material/ImageSearchOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import type { TextRecognitionConnectionType } from '@/shared/api/types'
import SectionHead from '@/shared/ui/SectionHead'
import ChoiceTileGroup, { type ChoiceTile } from '@/shared/ui/choice/ChoiceTileGroup'
import {
  TEXT_RECOGNITION_CONNECTION_TYPE_ORDER,
  TEXT_RECOGNITION_CONNECTION_TYPES,
  TEXT_RECOGNITION_PURPOSE,
  TEXT_RECOGNITION_VISION_NOTICE,
} from '@/shared/ui/textRecognition/textRecognitionTexts'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import type { InstallationStepState, StepInput } from '../../api/types'
import type { WizardResult } from '../../hooks/useInstallationWizard'
import { useStepForm } from '../../hooks/useStepForm'
import { apiKeyPart } from '../../utils/apiKey'
import { STEP_INFO } from '../../utils/steps'
import { compact, isBlank, urlError } from '../../utils/validation'
import ApiKeyField from '../ApiKeyField'
import FormField from '../FormField'
import StepFrame, { type StepNavigationProps } from '../StepFrame'
import { STEP_RHYTHM } from '../stepLayout'

interface TextRecognitionModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['TEXT_RECOGNITION_MODEL']) => Promise<WizardResult>
}

/** The address of the bundled service in the Compose stack; Kubernetes names it `<release>-ocr`. */
const BUNDLED_TEXT_RECOGNITION_ADDRESS = 'http://opaa-ocr:5001'

/** An address of the bundled service: `opaa-ocr` (Compose) or `<release>-ocr` (Helm), port 5001. */
const BUNDLED_ADDRESS_PATTERN = /^http:\/\/[a-z0-9.-]*ocr:5001\/?$/i

type Service = 'bundled' | 'own'

const SERVICE_TILES: ChoiceTile<Service>[] = [
  {
    value: 'bundled',
    label: 'Mitgelieferter Dienst',
    description: 'opaa-ocr aus dem Compose-Stapel oder dem Helm-Chart; nichts verlässt das Haus.',
    icon: <DnsOutlinedIcon fontSize="small" />,
  },
  {
    value: 'own',
    label: 'Eigener Endpunkt',
    description: 'Ein vorhandenes docling-serve oder ein OpenAI-kompatibles Vision-Modell.',
    icon: <LinkOutlinedIcon fontSize="small" />,
  },
]

const CONNECTION_ICONS: Record<TextRecognitionConnectionType, ChoiceTile<string>['icon']> = {
  DOCLING_SERVE: <DocumentScannerOutlinedIcon fontSize="small" />,
  OPENAI_VISION: <ImageSearchOutlinedIcon fontSize="small" />,
}

const CONNECTION_TILES: ChoiceTile<TextRecognitionConnectionType>[] =
  TEXT_RECOGNITION_CONNECTION_TYPE_ORDER.map((type) => ({
    value: type,
    label: TEXT_RECOGNITION_CONNECTION_TYPES[type].label,
    description: TEXT_RECOGNITION_CONNECTION_TYPES[type].description,
    icon: CONNECTION_ICONS[type],
  }))

const BUNDLED_ADDRESS_HINT =
  'Docker Compose: http://opaa-ocr:5001. Kubernetes: http://<Release>-ocr:5001, wie es helm install in seinen Hinweisen nennt.'

const BUNDLED_KEY_HINT =
  'Derselbe Schlüssel wie beim Dienst: OPAA_OCR_API_KEY im Compose-Stapel, ocr-api-key im Secret des Charts. OPAA liest ihn nicht von dort; er wird hier eingetragen und verschlüsselt gespeichert.'

const TEXT_RECOGNITION_FIELDS = ['connectionType', 'baseUrl', 'model', 'apiKey']

function serviceOf(stored: InstallationStepState['textRecognitionModel'] | null): Service {
  if (!stored) return 'bundled'
  return stored.connectionType === 'DOCLING_SERVE' && BUNDLED_ADDRESS_PATTERN.test(stored.baseUrl)
    ? 'bundled'
    : 'own'
}

/**
 * The text recognition (OCR) of scanned PDFs, with the connection types and rules of „Modelle →
 * Texterkennung". The bundled service comes first, its address filled in; an own endpoint is
 * docling-serve or a vision model. The check is the staged test of the administration with its
 * test page. The API lists the step only while the operation releases text recognition.
 */
export default function TextRecognitionModelStep({
  step,
  submit,
  onBack,
  onSkip,
}: TextRecognitionModelStepProps) {
  const id = useId()
  const stored = step.textRecognitionModel ?? null
  const [service, setService] = useState<Service>(() => serviceOf(stored))
  const [connectionType, setConnectionType] = useState<TextRecognitionConnectionType>(
    stored?.connectionType ?? 'DOCLING_SERVE',
  )
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? BUNDLED_TEXT_RECOGNITION_ADDRESS)
  const [model, setModel] = useState(stored?.model ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const isVision = service === 'own' && connectionType === 'OPENAI_VISION'

  /** The bundled service speaks docling-serve at its own address; an own one starts empty. */
  function chooseService(next: Service) {
    setService(next)
    if (next === 'bundled') {
      setConnectionType('DOCLING_SERVE')
      if (!BUNDLED_ADDRESS_PATTERN.test(baseUrl.trim()))
        setBaseUrl(BUNDLED_TEXT_RECOGNITION_ADDRESS)
    } else if (BUNDLED_ADDRESS_PATTERN.test(baseUrl.trim())) {
      setBaseUrl('')
    }
  }

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isVision && isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
    })
    void form.submit(localErrors, TEXT_RECOGNITION_FIELDS, () =>
      submit({
        connectionType: service === 'bundled' ? 'DOCLING_SERVE' : connectionType,
        baseUrl: baseUrl.trim(),
        model: isVision ? model.trim() : null,
        ...apiKeyPart(apiKey, removeKey),
      }),
    )
  }

  const serviceLabelId = `${id}-service`
  const connectionLabelId = `${id}-connection`
  const addressHint =
    service === 'bundled'
      ? BUNDLED_ADDRESS_HINT
      : TEXT_RECOGNITION_CONNECTION_TYPES[connectionType].addressHint

  return (
    <StepFrame
      title={STEP_INFO.TEXT_RECOGNITION_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.TEXT_RECOGNITION_MODEL.laterUnder}
      description={`${TEXT_RECOGNITION_PURPOSE} Die Prüfung lässt den Dienst eine mitgelieferte Testseite lesen, in den Stufen Adresse, Anmeldung und Erkennung; die erste Umwandlung kann einige Minuten dauern. Ohne Texterkennung bleiben solche Scans unauffindbar, bis die Systemverwaltung sie unter Modelle → Texterkennung einträgt.`}
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Dienst wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <Box component="section" aria-labelledby={`${id}-endpoint`}>
        <SectionHead component="h3" id={`${id}-endpoint`}>
          Endpunkt
        </SectionHead>
        <Stack spacing={STEP_RHYTHM.betweenGroups}>
          <Box>
            <FieldLabel id={serviceLabelId}>Dienst</FieldLabel>
            <ChoiceTileGroup<Service>
              aria-labelledby={serviceLabelId}
              tiles={SERVICE_TILES}
              value={service}
              onChange={chooseService}
              columns={2}
            />
          </Box>
          {service === 'own' && (
            <Box>
              <FieldLabel id={connectionLabelId}>Anbindung</FieldLabel>
              <ChoiceTileGroup<TextRecognitionConnectionType>
                aria-labelledby={connectionLabelId}
                tiles={CONNECTION_TILES}
                value={connectionType}
                onChange={setConnectionType}
                columns={2}
              />
              {isVision && (
                <Alert severity="info" sx={{ mt: STEP_RHYTHM.fieldToOwnAction }}>
                  {TEXT_RECOGNITION_VISION_NOTICE}
                </Alert>
              )}
            </Box>
          )}
          <FormField
            id={`${id}-base-url`}
            label="Basis-Adresse"
            inputMode="url"
            value={baseUrl}
            onChange={setBaseUrl}
            error={form.errors.baseUrl}
            hint={addressHint}
          />
          {isVision && (
            <FormField
              id={`${id}-model`}
              label="Modell-Kennung"
              value={model}
              onChange={setModel}
              error={form.errors.model}
              maxLength={200}
            />
          )}
          <ApiKeyField
            id={`${id}-api-key`}
            keyStored={Boolean(stored?.apiKeySet)}
            value={apiKey}
            onChange={setApiKey}
            remove={removeKey}
            onRemoveChange={setRemoveKey}
            error={form.errors.apiKey}
            hint={service === 'bundled' ? BUNDLED_KEY_HINT : undefined}
          />
        </Stack>
      </Box>
    </StepFrame>
  )
}
