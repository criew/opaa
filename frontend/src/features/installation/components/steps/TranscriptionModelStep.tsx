import { useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import type { LlmModelDiscoveryResponse, RealtimeTranscriptionProtocol } from '@/shared/api/types'
import ChoiceTileGroup from '@/shared/ui/choice/ChoiceTileGroup'
import SectionHead from '@/shared/ui/SectionHead'
import {
  afterProtocolSwitch,
  batchPrefillFor,
  batchUntouched,
  liveKeyMissing,
  REALTIME_PROTOCOL_PRESETS,
  REALTIME_TEXTS,
  realtimePresetOf,
  realtimeUrlError,
  sharesOrigin,
  type BatchPrefill,
} from '@/shared/ui/transcription/realtimeTranscription'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
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
import { originOf, providerOf } from '../../utils/modelEndpoint'
import { compact, isBlank, urlError } from '../../utils/validation'
import ApiKeyField from '../ApiKeyField'
import FormField from '../FormField'
import ModelEndpointFields from '../ModelEndpointFields'
import StepFrame, { type StepNavigationProps } from '../StepFrame'
import { STEP_RHYTHM } from '../stepLayout'
import { fontSize } from '@/shared/theme/tokens'

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
  const storedLive = stored?.realtime ?? null
  const [live, setLive] = useState(storedLive !== null)
  const [protocol, setProtocol] = useState<RealtimeTranscriptionProtocol | null>(
    storedLive?.protocol ?? null,
  )
  const [liveUrl, setLiveUrl] = useState(storedLive?.url ?? '')
  const [liveModel, setLiveModel] = useState(storedLive?.model ?? '')
  const [liveKey, setLiveKey] = useState('')
  const [removeLiveKey, setRemoveLiveKey] = useState(false)
  // The dictation endpoint last filled in for the live service, and a key that remounts the
  // endpoint fields so their provider tile follows the filled address.
  const [prefill, setPrefill] = useState<BatchPrefill | null>(null)
  const [endpointKey, setEndpointKey] = useState(0)
  const form = useStepForm(step.checked ? null : step.checkMessage)
  const preset = realtimePresetOf(protocol)
  // The endpoint fields already warn about an external dictation service; the live notice only
  // repeats it for a live service on another host.
  const batchExternal = providerOf(baseUrl, 'transcription') === 'openai'

  /**
   * Fills the dictation endpoint for a live service while it is still empty or holds the last fill,
   * so an installation with only a live service needs no second address; the dictation endpoint
   * stays mandatory. A typed key does not follow a fill to another origin.
   */
  function fillBatch(next: RealtimeTranscriptionProtocol | null, url: string, liveName: string) {
    if (!batchUntouched(baseUrl, model, prefill)) return
    const fill = batchPrefillFor(next, url, liveName)
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

  function switchLive(on: boolean) {
    setLive(on)
    if (!on) return
    if (protocol === null) chooseProtocol(REALTIME_PROTOCOL_PRESETS[0].protocol)
    else fillBatch(protocol, liveUrl, liveModel)
  }

  function chooseProtocol(next: RealtimeTranscriptionProtocol) {
    const filled = afterProtocolSwitch(protocol, next, liveUrl, liveModel)
    setProtocol(next)
    setLiveUrl(filled.url)
    setLiveModel(filled.model)
    fillBatch(next, filled.url, filled.model)
  }

  function typeLiveUrl(next: string) {
    setLiveUrl(next)
    fillBatch(protocol, next, liveModel)
  }

  function typeLiveModel(next: string) {
    setLiveModel(next)
    fillBatch(protocol, liveUrl, next)
  }

  function handleSubmit() {
    const localErrors = compact({
      baseUrl: urlError(baseUrl, 'die Basis-Adresse'),
      model: isBlank(model) ? 'Bitte die Modell-Kennung angeben.' : undefined,
      language: transcriptionLanguageError(language),
      'realtime.protocol':
        live && protocol === null ? 'Bitte einen Live-Dienst wählen.' : undefined,
      'realtime.url': live ? realtimeUrlError(liveUrl) : undefined,
      'realtime.model': live && isBlank(liveModel) ? 'Bitte das Live-Modell angeben.' : undefined,
      'realtime.apiKey':
        live &&
        liveKeyMissing({
          protocol,
          liveUrl,
          ownKey: liveKey !== '' || (Boolean(storedLive?.apiKeySet) && !removeLiveKey),
          batchUrl: baseUrl,
          batchKey: apiKey !== '' || (Boolean(stored?.apiKeySet) && !removeKey),
        })
          ? REALTIME_TEXTS.keyMissing
          : undefined,
    })
    void form.submit(localErrors, TRANSCRIPTION_MODEL_FIELDS, () =>
      submit({
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        ...apiKeyPart(apiKey, removeKey),
        language: language.trim(),
        realtime:
          live && protocol !== null
            ? {
                protocol,
                url: liveUrl.trim(),
                model: liveModel.trim(),
                ...apiKeyPart(liveKey, removeLiveKey),
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
      description={`${MODEL_ROLE_TEXTS.transcription.purpose} Die Prüfung fragt den Endpunkt nach seinen Modellen und sendet keine Aufnahme; ein Live-Dienst wird mit einer Sitzung ohne Audio geprüft. Ohne Transkriptionsmodell bleibt die Spracheingabe aus, bis die Systemverwaltung eines unter Modelle → Spracheingabe einträgt.`}
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
      <Box
        component="section"
        aria-labelledby={`${id}-live`}
        sx={{ mt: STEP_RHYTHM.betweenGroups }}
      >
        <SectionHead component="h3" id={`${id}-live`}>
          {REALTIME_TEXTS.sectionLabel}
        </SectionHead>
        <Stack spacing={STEP_RHYTHM.betweenGroups}>
          <Box>
            <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
              {REALTIME_TEXTS.alwaysDictation}
            </Typography>
            <FormControlLabel
              sx={{ mt: STEP_RHYTHM.fieldToOwnAction }}
              control={
                <Switch
                  checked={live}
                  onChange={(e) => switchLive(e.target.checked)}
                  slotProps={{ input: { 'aria-describedby': `${id}-live-hint` } }}
                />
              }
              label={REALTIME_TEXTS.switchLabel}
            />
            <Typography
              id={`${id}-live-hint`}
              sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}
            >
              {REALTIME_TEXTS.switchHint}
            </Typography>
          </Box>
          {live && (
            <>
              <Box>
                <FieldLabel id={`${id}-live-protocol`}>{REALTIME_TEXTS.protocolLabel}</FieldLabel>
                <ChoiceTileGroup<RealtimeTranscriptionProtocol>
                  aria-labelledby={`${id}-live-protocol`}
                  tiles={REALTIME_PROTOCOL_PRESETS.map((option) => ({
                    value: option.protocol,
                    label: option.label,
                    description: option.description,
                    icon: option.external ? (
                      <CloudOutlinedIcon fontSize="small" />
                    ) : (
                      <DnsOutlinedIcon fontSize="small" />
                    ),
                  }))}
                  value={protocol}
                  onChange={chooseProtocol}
                  columns={3}
                />
                {form.errors['realtime.protocol'] && (
                  <Alert severity="error" sx={{ mt: STEP_RHYTHM.fieldToOwnAction }}>
                    {form.errors['realtime.protocol']}
                  </Alert>
                )}
                {live && prefill !== null && (
                  <Alert severity="info" sx={{ mt: STEP_RHYTHM.fieldToOwnAction }}>
                    {REALTIME_TEXTS.batchPrefilled}
                  </Alert>
                )}
                {preset?.external && !(batchExternal && sharesOrigin(liveUrl, baseUrl)) && (
                  <Alert severity="warning" sx={{ mt: STEP_RHYTHM.fieldToOwnAction }}>
                    {REALTIME_TEXTS.externalService}
                  </Alert>
                )}
              </Box>
              <FormField
                id={`${id}-live-url`}
                label={REALTIME_TEXTS.urlLabel}
                inputMode="url"
                value={liveUrl}
                onChange={typeLiveUrl}
                error={form.errors['realtime.url']}
                hint={REALTIME_TEXTS.urlHint}
              />
              <FormField
                id={`${id}-live-model`}
                label={REALTIME_TEXTS.modelLabel}
                value={liveModel}
                onChange={typeLiveModel}
                error={form.errors['realtime.model']}
              />
              <ApiKeyField
                id={`${id}-live-key`}
                keyStored={Boolean(storedLive?.apiKeySet)}
                value={liveKey}
                onChange={setLiveKey}
                remove={removeLiveKey}
                onRemoveChange={setRemoveLiveKey}
                error={form.errors['realtime.apiKey']}
                hint={REALTIME_TEXTS.keyHint}
                wording={{
                  label: REALTIME_TEXTS.keyLabel,
                  removed: 'Der gespeicherte Live-Schlüssel wird beim Speichern entfernt.',
                  keep: 'Live-Schlüssel behalten',
                  remove: 'Gespeicherten Live-Schlüssel entfernen',
                }}
              />
            </>
          )}
        </Stack>
      </Box>
    </StepFrame>
  )
}
