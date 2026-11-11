import { useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import DocumentScannerOutlinedIcon from '@mui/icons-material/DocumentScannerOutlined'
import ImageSearchOutlinedIcon from '@mui/icons-material/ImageSearchOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import type { TextRecognitionConnectionType } from '../../api/types'
import ChoiceTileGroup, { type ChoiceTile } from '../choice/ChoiceTileGroup'
import FormFieldLabel from '../form/FormFieldLabel'
import SecretField from '../form/SecretField'
import {
  TEXT_RECOGNITION_CONNECTION_TYPE_ORDER,
  TEXT_RECOGNITION_CONNECTION_TYPES,
  TEXT_RECOGNITION_VISION_NOTICE,
} from '../textRecognition/textRecognitionTexts'
import SetupField from './SetupField'
import {
  BUNDLED_ADDRESS_PATTERN,
  BUNDLED_TEXT_RECOGNITION_ADDRESS,
  type TextRecognitionDraft,
} from './setupDrafts'
import { API_KEY_LABEL, SETUP_RHYTHM, type SetupErrors } from './setupNames'

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
  'Docker Compose: http://opaa-ocr:5001. Kubernetes: http://<Release>-ocr:5001.'

const BUNDLED_KEY_HINT =
  'Derselbe Schlüssel wie beim Dienst: OPAA_OCR_API_KEY (Compose) oder ocr-api-key im Secret des Charts.'

function serviceOf(draft: TextRecognitionDraft): Service {
  return draft.connectionType === 'DOCLING_SERVE' && BUNDLED_ADDRESS_PATTERN.test(draft.baseUrl)
    ? 'bundled'
    : 'own'
}

interface TextRecognitionFieldsProps {
  value: TextRecognitionDraft
  onChange: (patch: Partial<TextRecognitionDraft>) => void
  apiKeyStored: boolean
  /** Errors by `connectionType`, `baseUrl`, `model` and `apiKey`. */
  errors?: SetupErrors
}

/**
 * The text recognition service as the assistant's step „Texterkennung“ and „Modelle →
 * Texterkennung“ ask for it: the bundled service first with its address filled in, or an endpoint
 * of one's own as docling-serve or vision model, then address, model and key.
 */
export default function TextRecognitionFields({
  value,
  onChange,
  apiKeyStored,
  errors = {},
}: TextRecognitionFieldsProps) {
  const id = useId()
  const [service, setService] = useState<Service>(() => serviceOf(value))
  const isVision = service === 'own' && value.connectionType === 'OPENAI_VISION'
  const serviceLabelId = `${id}-service`
  const connectionLabelId = `${id}-connection`

  /** The bundled service speaks docling-serve at its own address; an own one starts empty. */
  function chooseService(next: Service) {
    setService(next)
    const bundledAddress = BUNDLED_ADDRESS_PATTERN.test(value.baseUrl.trim())
    if (next === 'bundled') {
      onChange({
        connectionType: 'DOCLING_SERVE',
        ...(bundledAddress ? {} : { baseUrl: BUNDLED_TEXT_RECOGNITION_ADDRESS }),
      })
    } else if (bundledAddress) {
      onChange({ baseUrl: '' })
    }
  }

  return (
    <Stack spacing={SETUP_RHYTHM.betweenFields}>
      <Box>
        <FormFieldLabel id={serviceLabelId}>Dienst</FormFieldLabel>
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
          <FormFieldLabel id={connectionLabelId}>Anbindung</FormFieldLabel>
          <ChoiceTileGroup<TextRecognitionConnectionType>
            aria-labelledby={connectionLabelId}
            tiles={CONNECTION_TILES}
            value={value.connectionType}
            onChange={(connectionType) => onChange({ connectionType })}
            columns={2}
          />
          {isVision && (
            <Alert severity="info" sx={{ mt: SETUP_RHYTHM.fieldToOwn }}>
              {TEXT_RECOGNITION_VISION_NOTICE}
            </Alert>
          )}
        </Box>
      )}
      <SetupField
        id={`${id}-base-url`}
        label="Basis-Adresse"
        inputMode="url"
        value={value.baseUrl}
        onChange={(baseUrl) => onChange({ baseUrl })}
        error={errors.baseUrl}
        hint={
          service === 'bundled'
            ? BUNDLED_ADDRESS_HINT
            : TEXT_RECOGNITION_CONNECTION_TYPES[value.connectionType].addressHint
        }
      />
      {isVision && (
        <SetupField
          id={`${id}-model`}
          label="Modell-Kennung"
          value={value.model}
          onChange={(model) => onChange({ model })}
          error={errors.model}
          maxLength={200}
        />
      )}
      <SecretField
        id={`${id}-api-key`}
        label={API_KEY_LABEL}
        optional
        autoComplete="off"
        value={value.apiKey}
        onChange={(apiKey) => onChange({ apiKey })}
        stored={apiKeyStored}
        error={errors.apiKey}
        hint={service === 'bundled' ? BUNDLED_KEY_HINT : undefined}
        removal={{
          requested: value.removeKey,
          onChange: (removeKey) => onChange({ removeKey }),
          removeLabel: 'Gespeicherten Schlüssel entfernen',
          keepLabel: 'Schlüssel behalten',
          removedText: 'Der gespeicherte Schlüssel wird beim Speichern entfernt.',
        }}
      />
    </Stack>
  )
}
