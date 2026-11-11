import { useEffect, useState } from 'react'
import { secretPart } from '@/shared/ui/setup/setupDrafts'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Checkbox from '@mui/material/Checkbox'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type {
  LlmModelTestResponse,
  SearchModelRoleState,
  TranscriptionModelSettingsResponse,
} from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { isVocabularyTooLong } from '@/shared/ui/transcription/transcriptionSettings'
import VocabularyField from '@/shared/ui/transcription/VocabularyField'
import ModelEndpointFields from '@/shared/ui/setup/ModelEndpointFields'
import RealtimeTranscriptionFields, {
  TranscriptionLanguageField,
} from '@/shared/ui/setup/TranscriptionFields'
import { notify } from '@/shared/stores/notificationStore'
import { MODEL_ROLE_TEXTS, type ModelRoleKind } from '@/shared/ui/modelDiscovery/modelRoleTexts'
import {
  batchPrefillFor,
  batchUntouched,
  liveKeyMissing,
  NO_REALTIME,
  REALTIME_TEXTS,
  realtimeUrlError,
  type BatchPrefill,
  type RealtimeDraft,
} from '@/shared/ui/transcription/realtimeTranscription'
import {
  getRerankModelSettings,
  getTranscriptionModelSettings,
  testRerankModel,
  testTranscriptionModel,
  testTranscriptionRealtimeModel,
  updateRerankModelSettings,
  updateTranscriptionModelSettings,
} from '../api/modelApi'
import { fieldErrorsOf } from './roleForm'
import { fontSize, formWidth } from '@/shared/theme/tokens'
import ConnectionTestButton from '@/shared/ui/settings/ConnectionTestButton'
import SettingsForm from '@/shared/ui/settings/SettingsForm'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

export type { ModelRoleKind }

/** How long after a save the state is read again; the probe runs on the server meanwhile. */
const RECHECK_DELAY_MS = 3000

/** What the section shows of a stored role; the rerank role has no language. */
type RoleSettings = Omit<
  TranscriptionModelSettingsResponse,
  'language' | 'vocabulary' | 'speechInputAvailable' | 'realtime'
> & {
  language?: string
  vocabulary?: string
  speechInputAvailable?: boolean
  realtime?: TranscriptionModelSettingsResponse['realtime']
}

interface RoleTexts {
  switchLabel: string
  intro: string
}

const TEXTS: Record<ModelRoleKind, RoleTexts> = {
  rerank: {
    switchLabel: 'Reranking einschalten',
    intro: `${MODEL_ROLE_TEXTS.rerank.purpose} Ab Werk ist es aus; ohne Rerank-Modell sucht OPAA ohne diese Stufe weiter.`,
  },
  transcription: {
    switchLabel: 'Spracheingabe einschalten',
    intro: `${MODEL_ROLE_TEXTS.transcription.purpose} Ab Werk ist es aus; das Mikrofon erscheint erst, wenn das Modell antwortet.`,
  },
}

const STATE_TEXT: Record<SearchModelRoleState, string> = {
  DISABLED: 'Ausgeschaltet.',
  ACTIVE: 'Eingeschaltet, der Endpunkt antwortet.',
  UNCONFIGURED: 'Eingeschaltet, aber Basis-Adresse oder Modell fehlen.',
  UNREACHABLE: 'Eingeschaltet, aber der Endpunkt antwortet noch nicht.',
}

const LIVE_STATE_TEXT: Record<SearchModelRoleState, string> = {
  DISABLED: 'Ausgeschaltet; das Diktat verarbeitet die ganze Aufnahme.',
  ACTIVE: 'Der Live-Dienst nimmt Sitzungen an.',
  UNCONFIGURED: 'Live-Transkript gewählt, aber Adresse oder Modell fehlen.',
  UNREACHABLE:
    'Live-Transkript gewählt, aber der Live-Dienst antwortet noch nicht; diktiert wird mit ganzer Aufnahme.',
}

function realtimeDraftOf(stored: RoleSettings['realtime']): RealtimeDraft {
  if (!stored?.enabled) return NO_REALTIME
  return {
    live: true,
    protocol: stored.protocol ?? null,
    url: stored.url ?? '',
    model: stored.model ?? '',
  }
}

function load(kind: ModelRoleKind): Promise<RoleSettings> {
  return kind === 'rerank' ? getRerankModelSettings() : getTranscriptionModelSettings()
}

/**
 * The rerank or the transcription model role in one form: switch, the endpoint and model fields
 * of the assistant's step (`ModelEndpointFields`) and — for dictation — language, the
 * installation's vocabulary list and the live service, with a connection test and the role's last
 * known state. The key is write-only: it reads „Gesetzt“ until it is replaced or removed.
 */
export default function ModelRoleSection({ kind }: { kind: ModelRoleKind }) {
  const texts = TEXTS[kind]
  const [settings, setSettings] = useState<RoleSettings | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [enabled, setEnabled] = useState(false)
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [language, setLanguage] = useState('')
  const [vocabulary, setVocabulary] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  // Remounts the endpoint fields after a save, so a stored key reads „Gesetzt“ again.
  const [savedCount, setSavedCount] = useState(0)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<LlmModelTestResponse | null>(null)
  // A saved, switched-on role is probed right after the save; its state is read once more then.
  const [checking, setChecking] = useState(false)
  const [realtime, setRealtime] = useState<RealtimeDraft>(NO_REALTIME)
  const [liveKey, setLiveKey] = useState('')
  const [removeLiveKey, setRemoveLiveKey] = useState(false)
  const [maxSessions, setMaxSessions] = useState('1')
  const [liveTesting, setLiveTesting] = useState(false)
  const [liveTestResult, setLiveTestResult] = useState<LlmModelTestResponse | null>(null)
  const [prefill, setPrefill] = useState<BatchPrefill | null>(null)

  function apply(stored: RoleSettings) {
    setSettings(stored)
    setEnabled(stored.enabled)
    setBaseUrl(stored.baseUrl ?? '')
    setModel(stored.model ?? '')
    setLanguage(stored.language ?? '')
    setVocabulary(stored.vocabulary ?? '')
    setApiKey('')
    setRemoveKey(false)
    setRealtime(realtimeDraftOf(stored.realtime))
    setLiveKey('')
    setRemoveLiveKey(false)
    setSavedCount((count) => count + 1)
    setPrefill(null)
    setMaxSessions(String(stored.realtime?.maxSessions ?? 1))
  }

  /**
   * Fills the dictation endpoint for the live service while it is empty or holds the last fill,
   * as the installation step does; the dictation endpoint stays mandatory.
   */
  function fillBatch(next: RealtimeDraft) {
    if (!batchUntouched(baseUrl, model, prefill)) return
    const fill = batchPrefillFor(next.protocol, next.url, next.model)
    if (fill === null) {
      if (prefill === null) return
      setBaseUrl('')
      setModel('')
      setPrefill(null)
      return
    }
    setBaseUrl(fill.baseUrl)
    setModel(fill.model)
    setPrefill(fill)
  }

  function realtimeRequest() {
    if (!realtime.live) return { enabled: false }
    const sessions = Number.parseInt(maxSessions, 10)
    return {
      enabled: true,
      protocol: realtime.protocol,
      url: realtime.url.trim(),
      model: realtime.model.trim(),
      apiKey: secretPart(liveKey.trim(), removeLiveKey),
      maxSessions: Number.isNaN(sessions) ? undefined : sessions,
    }
  }

  useEffect(() => {
    let current = true
    load(kind)
      .then((stored) => current && apply(stored))
      .catch((err: unknown) => {
        if (current) setLoadError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      current = false
    }
  }, [kind, attempt])

  useEffect(() => {
    if (!checking) return
    let current = true
    const timer = setTimeout(() => {
      load(kind)
        .then((stored) => current && setSettings(stored))
        .catch(() => undefined)
        .finally(() => current && setChecking(false))
    }, RECHECK_DELAY_MS)
    return () => {
      current = false
      clearTimeout(timer)
    }
  }, [checking, kind])

  async function handleSave() {
    setErrors({})
    setError(null)
    // the vocabulary field itself tells that the list is too long
    if (kind === 'transcription' && isVocabularyTooLong(vocabulary)) return
    if (
      kind === 'transcription' &&
      enabled &&
      realtime.live &&
      liveKeyMissing({
        protocol: realtime.protocol,
        liveUrl: realtime.url,
        ownKey: liveKey !== '' || (Boolean(settings?.realtime?.apiKeySet) && !removeLiveKey),
        batchUrl: baseUrl,
        batchKey: apiKey !== '' || (Boolean(settings?.apiKeySet) && !removeKey),
      })
    ) {
      setErrors({ 'realtime.apiKey': REALTIME_TEXTS.keyMissing })
      return
    }
    setSaving(true)
    const common = {
      enabled,
      baseUrl: baseUrl.trim(),
      model: model.trim(),
      apiKey: secretPart(apiKey.trim(), removeKey),
    }
    try {
      const saved =
        kind === 'rerank'
          ? await updateRerankModelSettings(common)
          : await updateTranscriptionModelSettings({
              ...common,
              language: language.trim(),
              vocabulary: vocabulary.trim(),
              realtime: realtimeRequest(),
            })
      apply(saved)
      setChecking(saved.enabled && saved.state !== 'ACTIVE')
      notify('Das Modell wurde gespeichert.', 'success')
    } catch (err) {
      setErrors(fieldErrorsOf(err))
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function handleTest() {
    const missing = {
      ...(baseUrl.trim() === '' ? { baseUrl: 'Basis-Adresse fehlt' } : {}),
      ...(model.trim() === '' ? { model: 'Modell-Kennung fehlt' } : {}),
    }
    if (Object.keys(missing).length > 0) {
      setErrors((current) => ({ ...current, ...missing }))
      return
    }
    setTesting(true)
    setTestResult(null)
    const request = {
      baseUrl: baseUrl.trim(),
      model: model.trim(),
      apiKey: secretPart(apiKey.trim(), removeKey),
    }
    try {
      setTestResult(
        kind === 'rerank' ? await testRerankModel(request) : await testTranscriptionModel(request),
      )
    } catch (err) {
      setTestResult({
        success: false,
        message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
      })
    } finally {
      setTesting(false)
    }
  }

  async function handleLiveTest() {
    const urlProblem = realtimeUrlError(realtime.url)
    const modelMissing = realtime.model.trim() === ''
    if (urlProblem || modelMissing || realtime.protocol === null) {
      setErrors((current) => ({
        ...current,
        ...(urlProblem ? { 'realtime.url': urlProblem } : {}),
        ...(modelMissing ? { 'realtime.model': 'Modell-Kennung fehlt' } : {}),
        ...(realtime.protocol === null
          ? { 'realtime.protocol': 'Bitte einen Live-Dienst wählen.' }
          : {}),
      }))
      return
    }
    setLiveTesting(true)
    setLiveTestResult(null)
    try {
      setLiveTestResult(
        await testTranscriptionRealtimeModel({
          protocol: realtime.protocol,
          url: realtime.url.trim(),
          model: realtime.model.trim(),
          apiKey: secretPart(liveKey.trim(), removeLiveKey),
        }),
      )
    } catch (err) {
      setLiveTestResult({
        success: false,
        message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
      })
    } finally {
      setLiveTesting(false)
    }
  }

  if (loadError) {
    return (
      <LoadError
        message="Die Einstellungen konnten nicht geladen werden."
        detail={loadError}
        onRetry={() => {
          setLoadError(null)
          setAttempt((n) => n + 1)
        }}
      />
    )
  }
  if (!settings) return <LoadingState shape="lines" count={4} />

  const speechInputOff = kind === 'transcription' && settings.speechInputAvailable === false
  const prefix = `model-role-${kind}`
  // the entries against what `apply` would set from the saved state
  const dirty =
    JSON.stringify([
      enabled,
      baseUrl,
      model,
      language,
      vocabulary,
      apiKey,
      removeKey,
      realtime,
      liveKey,
      removeLiveKey,
      maxSessions,
    ]) !==
    JSON.stringify([
      settings.enabled,
      settings.baseUrl ?? '',
      settings.model ?? '',
      settings.language ?? '',
      settings.vocabulary ?? '',
      '',
      false,
      realtimeDraftOf(settings.realtime),
      '',
      false,
      String(settings.realtime?.maxSessions ?? 1),
    ])

  function discard() {
    apply(settings!)
    setErrors({})
    setError(null)
    setTestResult(null)
    setLiveTestResult(null)
  }

  return (
    <Box sx={{ maxWidth: formWidth.column }}>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 2 }}>
        {texts.intro}
      </Typography>
      {settings.retiredVariables.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Im Betrieb ist noch {settings.retiredVariables.join(', ')} gesetzt. Diese Angaben werden
          seit dem Update nicht mehr gelesen und nicht übernommen; es gilt, was hier gespeichert
          ist. Bitte die Werte hier eintragen und die Variablen entfernen.
        </Alert>
      )}
      {speechInputOff && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Der Betrieb hat die Spracheingabe abgeschaltet (OPAA_FEATURES_SPEECH_INPUT_ENABLED).
          Endpunkt und Modell lassen sich vorbereiten, einschalten lässt sich die Rolle erst, wenn
          der Betrieb sie freigibt.
        </Alert>
      )}
      <Typography role="status" sx={{ fontSize: fontSize.text, mb: 2 }}>
        Zustand: {checking ? 'Wird geprüft …' : STATE_TEXT[settings.state]}
      </Typography>
      <SettingsForm
        aria-label={texts.switchLabel}
        onSave={handleSave}
        busy={saving}
        dirty={dirty}
        onDiscard={discard}
        secondaryAction={<ConnectionTestButton busy={testing} onTest={() => void handleTest()} />}
        result={
          (testResult || error) && (
            <Stack spacing={2}>
              {testResult && (
                <Alert severity={testResult.success ? 'success' : 'error'}>
                  {testResult.message}
                </Alert>
              )}
              {error && <Alert severity="error">{error}</Alert>}
            </Stack>
          )
        }
      >
        <Stack spacing={3}>
          <FormControlLabel
            control={
              <Checkbox
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                disabled={speechInputOff && !settings.enabled}
              />
            }
            label={texts.switchLabel}
          />
          <ModelEndpointFields
            key={savedCount}
            purpose={kind}
            stored={{ baseUrl: settings.baseUrl ?? '', apiKeySet: settings.apiKeySet }}
            baseUrl={baseUrl}
            onBaseUrlChange={(next) => {
              setBaseUrl(next)
              setTestResult(null)
            }}
            apiKey={apiKey}
            onApiKeyChange={setApiKey}
            removeKey={removeKey}
            onRemoveKeyChange={setRemoveKey}
            model={model}
            onModelChange={setModel}
            errors={errors}
          >
            {kind === 'transcription' && (
              <TranscriptionLanguageField
                id={`${prefix}-language`}
                value={language}
                onChange={setLanguage}
                error={errors.language}
              />
            )}
            {kind === 'transcription' && (
              <VocabularyField
                id={`${prefix}-vocabulary`}
                value={vocabulary}
                onChange={setVocabulary}
                error={errors.vocabulary}
                hint="Gilt für alle; persönliche Begriffe pflegt jede Person in ihren Einstellungen."
              />
            )}
          </ModelEndpointFields>
          {kind === 'transcription' && (
            <RealtimeTranscriptionFields
              idPrefix={`${prefix}-live`}
              value={realtime}
              onChange={(next) => {
                setRealtime(next)
                setLiveTestResult(null)
                if (next.live) fillBatch(next)
              }}
              liveKey={liveKey}
              onLiveKeyChange={setLiveKey}
              removeLiveKey={removeLiveKey}
              onRemoveLiveKeyChange={setRemoveLiveKey}
              liveKeyStored={settings.realtime?.apiKeySet ?? false}
              errors={{
                protocol: errors['realtime.protocol'],
                url: errors['realtime.url'],
                model: errors['realtime.model'],
                apiKey: errors['realtime.apiKey'],
              }}
              batchPrefilled={prefill !== null}
              status={
                <Typography role="status" sx={{ fontSize: fontSize.text, mb: 1 }}>
                  Zustand: {LIVE_STATE_TEXT[settings.realtime?.state ?? 'DISABLED']}
                </Typography>
              }
            >
              <FormField
                id={`${prefix}-live-max-sessions`}
                label="Gleichzeitige Live-Sitzungen"
                type="number"
                value={maxSessions}
                onChange={(e) => setMaxSessions(e.target.value)}
                error={Boolean(errors['realtime.maxSessions'])}
                helperText={
                  errors['realtime.maxSessions'] ??
                  'Für die ganze Installation, 1 bis 100; wer darüber diktiert, bekommt den Text nach der Aufnahme.'
                }
                slotProps={{ htmlInput: { min: 1, max: 100 } }}
              />
              {liveTestResult && (
                <Alert severity={liveTestResult.success ? 'success' : 'error'}>
                  {liveTestResult.message}
                </Alert>
              )}
              <Box>
                <BusyButton
                  variant="outlined"
                  busy={liveTesting}
                  busyAnnouncement="Live-Dienst wird getestet"
                  onClick={() => void handleLiveTest()}
                >
                  Live-Dienst testen
                </BusyButton>
              </Box>
            </RealtimeTranscriptionFields>
          )}
        </Stack>
      </SettingsForm>
    </Box>
  )
}
