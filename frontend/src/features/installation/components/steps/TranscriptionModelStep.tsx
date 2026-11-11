import { useId, useState } from 'react'
import Typography from '@mui/material/Typography'
import { secretPart } from '@/shared/ui/setup/setupDrafts'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import { originOf, providerOf } from '@/shared/ui/modelDiscovery/modelEndpoint'
import { MODEL_ROLE_TEXTS } from '@/shared/ui/modelDiscovery/modelRoleTexts'
import ModelEndpointFields from '@/shared/ui/setup/ModelEndpointFields'
import RealtimeTranscriptionFields, {
  TranscriptionLanguageField,
} from '@/shared/ui/setup/TranscriptionFields'
import {
  batchPrefillFor,
  batchUntouched,
  liveKeyMissing,
  NO_REALTIME,
  REALTIME_TEXTS,
  realtimeUrlError,
  sharesOrigin,
  type BatchPrefill,
  type RealtimeDraft,
} from '@/shared/ui/transcription/realtimeTranscription'
import { transcriptionLanguageError } from '@/shared/ui/transcription/transcriptionSettings'
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

interface TranscriptionModelStepProps extends StepNavigationProps {
  step: InstallationStepState
  submit: (values: StepInput['TRANSCRIPTION_MODEL']) => Promise<WizardResult>
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
}

const TRANSCRIPTION_MODEL_FIELDS = [
  'baseUrl',
  'model',
  'apiKey',
  'language',
  'realtime.protocol',
  'realtime.url',
  'realtime.model',
  'realtime.apiKey',
]
const DEFAULT_LANGUAGE = 'de'

/**
 * The model that turns dictated questions into text, with the fields and the language rule of
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
  const storedLive = stored?.realtime ?? null
  const [realtime, setRealtime] = useState<RealtimeDraft>(
    storedLive
      ? { live: true, protocol: storedLive.protocol, url: storedLive.url, model: storedLive.model }
      : NO_REALTIME,
  )
  const [liveKey, setLiveKey] = useState('')
  const [removeLiveKey, setRemoveLiveKey] = useState(false)
  // The dictation endpoint last filled in for the live service, and a key that remounts the
  // endpoint fields so their provider tile follows the filled address.
  const [prefill, setPrefill] = useState<BatchPrefill | null>(null)
  const [endpointKey, setEndpointKey] = useState(0)
  const form = useStepForm(step.checked ? null : step.checkMessage)
  // The endpoint fields already warn about an external dictation service; the live notice only
  // repeats it for a live service on another host.
  const batchExternal = providerOf(baseUrl, 'transcription') === 'openai'

  /**
   * Fills the dictation endpoint for a live service while it is still empty or holds the last fill,
   * so an installation with only a live service needs no second address; the dictation endpoint
   * stays mandatory. A typed key does not follow a fill to another origin.
   */
  function fillBatch(next: RealtimeDraft) {
    if (!batchUntouched(baseUrl, model, prefill)) return
    const fill = batchPrefillFor(next.protocol, next.url, next.model)
    if (fill === null) {
      if (prefill === null) return
      setBaseUrl('')
      setModel('')
      setPrefill(null)
      setEndpointKey((current) => current + 1)
      return
    }
    if (fill.baseUrl === baseUrl.trim() && fill.model === model.trim()) return
    if (apiKey !== '' && originOf(fill.baseUrl) !== originOf(baseUrl)) setApiKey('')
    setBaseUrl(fill.baseUrl)
    setModel(fill.model)
    setPrefill(fill)
    setEndpointKey((current) => current + 1)
  }

  function handleSubmit() {
    const { live, protocol } = realtime
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
      language: transcriptionLanguageError(language),
      'realtime.protocol':
        live && protocol === null ? 'Bitte einen Live-Dienst wählen.' : undefined,
      'realtime.url': live ? realtimeUrlError(realtime.url) : undefined,
      'realtime.model':
        live && isBlank(realtime.model) ? 'Bitte das Live-Modell angeben.' : undefined,
      'realtime.apiKey':
        live &&
        liveKeyMissing({
          protocol,
          liveUrl: realtime.url,
          ownKey: liveKey !== '' || (Boolean(storedLive?.apiKeySet) && !removeLiveKey),
          batchUrl: baseUrl,
          batchKey: apiKey !== '' || (Boolean(stored?.apiKeySet) && !removeKey),
        })
          ? REALTIME_TEXTS.keyMissing
          : undefined,
    })
    const key = secretPart(apiKey, removeKey)
    const ownLiveKey = secretPart(liveKey, removeLiveKey)
    void form.submit(localErrors, TRANSCRIPTION_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...(key === undefined ? {} : { apiKey: key }),
        language: language.trim(),
        realtime:
          live && protocol !== null
            ? {
                protocol,
                url: realtime.url.trim(),
                model: realtime.model.trim(),
                ...(ownLiveKey === undefined ? {} : { apiKey: ownLiveKey }),
              }
            : null,
      }),
    )
  }

  return (
    <StepFrame
      title={STEP_INFO.TRANSCRIPTION_MODEL.label}
      mandatory={step.mandatory}
      laterUnder={STEP_INFO.TRANSCRIPTION_MODEL.laterUnder}
      description={`${MODEL_ROLE_TEXTS.transcription.purpose} Übersprungen bleibt die Spracheingabe aus, bis die Systemverwaltung sie unter „Modelle → Spracheingabe“ einrichtet.`}
      failure={form.failure}
      busy={form.busy}
      busyAnnouncement="Endpunkt wird geprüft"
      onSubmit={handleSubmit}
      onBack={onBack}
      onSkip={onSkip}
    >
      <ModelEndpointFields
        key={endpointKey}
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
        <TranscriptionLanguageField
          id={`${id}-language`}
          value={language}
          onChange={setLanguage}
          error={form.errors.language}
        />
      </ModelEndpointFields>
      <RealtimeTranscriptionFields
        idPrefix={`${id}-live`}
        value={realtime}
        onChange={(next) => {
          setRealtime(next)
          if (next.live) fillBatch(next)
        }}
        liveKey={liveKey}
        onLiveKeyChange={setLiveKey}
        removeLiveKey={removeLiveKey}
        onRemoveLiveKeyChange={setRemoveLiveKey}
        liveKeyStored={Boolean(storedLive?.apiKeySet)}
        errors={{
          protocol: form.errors['realtime.protocol'],
          url: form.errors['realtime.url'],
          model: form.errors['realtime.model'],
          apiKey: form.errors['realtime.apiKey'],
        }}
        batchPrefilled={prefill !== null}
        externalNoticeGiven={batchExternal && sharesOrigin(realtime.url, baseUrl)}
      >
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Eine Live-Sitzung zur Zeit; mehr legen Sie später unter „Modelle → Spracheingabe“ fest.
        </Typography>
      </RealtimeTranscriptionFields>
    </StepFrame>
  )
}
