import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  LlmModelTestResponse,
  SearchModelRoleState,
  TranscriptionModelSettingsResponse,
} from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import {
  isVocabularyTooLong,
  TRANSCRIPTION_TEXTS,
} from '@/shared/ui/transcription/transcriptionSettings'
import VocabularyField from '@/shared/ui/transcription/VocabularyField'
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
import RealtimeTranscriptionFields from './RealtimeTranscriptionFields'
import RoleApiKeyField from './RoleApiKeyField'
import { fieldErrorsOf, resolveApiKey, UNTOUCHED_API_KEY, type ApiKeyDraft } from './roleForm'
import { fontSize } from '@/shared/theme/tokens'

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
  addressHint: string
}

const TEXTS: Record<ModelRoleKind, RoleTexts> = {
  rerank: {
    switchLabel: 'Reranking einschalten',
    intro: `${MODEL_ROLE_TEXTS.rerank.purpose} Ab Werk ist es aus; ohne Rerank-Modell sucht OPAA ohne diese Stufe weiter.`,
    addressHint: MODEL_ROLE_TEXTS.rerank.addressHint,
  },
  transcription: {
    switchLabel: 'Spracheingabe einschalten',
    intro: `${MODEL_ROLE_TEXTS.transcription.purpose} Ab Werk ist es aus; das Mikrofon erscheint erst, wenn das Modell antwortet.`,
    addressHint: MODEL_ROLE_TEXTS.transcription.addressHint,
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
 * The rerank or the transcription model role in one form: switch, endpoint, key and — for
 * dictation — language and the installation's vocabulary list, with a connection test and the
 * role's last known state. The key is write-only, as for the chat models: an untouched field
 * keeps it, „entfernen" removes it.
 */
export default function ModelRoleSection({ kind }: { kind: ModelRoleKind }) {
  const texts = TEXTS[kind]
  const [settings, setSettings] = useState<RoleSettings | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [language, setLanguage] = useState('')
  const [vocabulary, setVocabulary] = useState('')
  const [apiKey, setApiKey] = useState<ApiKeyDraft>(UNTOUCHED_API_KEY)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<LlmModelTestResponse | null>(null)
  // A saved, switched-on role is probed right after the save; its state is read once more then.
  const [checking, setChecking] = useState(false)
  const [realtime, setRealtime] = useState<RealtimeDraft>(NO_REALTIME)
  const [liveKey, setLiveKey] = useState<ApiKeyDraft>(UNTOUCHED_API_KEY)
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
    setApiKey(UNTOUCHED_API_KEY)
    setRealtime(realtimeDraftOf(stored.realtime))
    setLiveKey(UNTOUCHED_API_KEY)
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
      apiKey: resolveApiKey(liveKey),
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
  }, [kind])

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
    if (
      kind === 'transcription' &&
      enabled &&
      realtime.live &&
      liveKeyMissing({
        protocol: realtime.protocol,
        liveUrl: realtime.url,
        ownKey:
          liveKey.input !== '' ||
          (Boolean(settings?.realtime?.apiKeySet) && !liveKey.clearRequested),
        batchUrl: baseUrl,
        batchKey: apiKey.input !== '' || (Boolean(settings?.apiKeySet) && !apiKey.clearRequested),
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
      apiKey: resolveApiKey(apiKey),
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
    setTesting(true)
    setTestResult(null)
    const request = { baseUrl: baseUrl.trim(), model: model.trim(), apiKey: resolveApiKey(apiKey) }
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
    if (urlProblem || realtime.protocol === null) {
      setErrors((current) => ({
        ...current,
        ...(urlProblem ? { 'realtime.url': urlProblem } : {}),
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
          apiKey: resolveApiKey(liveKey),
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

  if (loadError) return <Alert severity="error">{loadError}</Alert>
  if (!settings) {
    return (
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
        Einstellungen werden geladen …
      </Typography>
    )
  }

  const speechInputOff = kind === 'transcription' && settings.speechInputAvailable === false
  const prefix = `model-role-${kind}`

  return (
    <Box sx={{ maxWidth: '72ch' }}>
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
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={2}>
        <FormControlLabel
          control={
            <Switch
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
              disabled={speechInputOff && !settings.enabled}
            />
          }
          label={texts.switchLabel}
        />
        <TextField
          id={`${prefix}-base-url`}
          label="Basis-Adresse"
          required={enabled}
          value={baseUrl}
          onChange={(e) => {
            setBaseUrl(e.target.value)
            setTestResult(null)
          }}
          error={Boolean(errors.baseUrl)}
          helperText={errors.baseUrl ?? texts.addressHint}
          size="small"
          fullWidth
        />
        <TextField
          id={`${prefix}-model`}
          label="Modell-Kennung"
          required={enabled}
          value={model}
          onChange={(e) => setModel(e.target.value)}
          error={Boolean(errors.model)}
          helperText={errors.model}
          size="small"
          fullWidth
        />
        <RoleApiKeyField
          id={`${prefix}-api-key`}
          value={apiKey}
          onChange={setApiKey}
          apiKeySet={settings.apiKeySet}
          error={errors.apiKey}
        />
        {kind === 'transcription' && (
          <TextField
            id={`${prefix}-language`}
            label={TRANSCRIPTION_TEXTS.languageLabel}
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            error={Boolean(errors.language)}
            helperText={errors.language ?? TRANSCRIPTION_TEXTS.languageHint}
            size="small"
            sx={{ maxWidth: 240 }}
          />
        )}
        {kind === 'transcription' && (
          <VocabularyField
            id={`${prefix}-vocabulary`}
            value={vocabulary}
            onChange={setVocabulary}
            error={errors.vocabulary}
            hint="Gilt für alle. Persönliche Begriffe pflegt jede Person in ihren Einstellungen; sie gehen nach dieser Liste an das Modell."
          />
        )}
        {testResult && (
          <Alert severity={testResult.success ? 'success' : 'error'}>{testResult.message}</Alert>
        )}
        <Box>
          <BusyButton
            variant="outlined"
            busy={testing}
            busyAnnouncement="Verbindung wird getestet"
            disabled={baseUrl.trim() === '' || model.trim() === ''}
            onClick={() => void handleTest()}
          >
            Verbindung testen
          </BusyButton>
        </Box>
        {kind === 'transcription' && (
          <Box component="section" aria-labelledby={`${prefix}-live-heading`}>
            <Typography
              id={`${prefix}-live-heading`}
              component="h3"
              sx={{ fontSize: fontSize.text, fontWeight: 600, mb: 0.5 }}
            >
              {REALTIME_TEXTS.sectionLabel}
            </Typography>
            <Typography role="status" sx={{ fontSize: fontSize.text, mb: 1.5 }}>
              Zustand: {LIVE_STATE_TEXT[settings.realtime?.state ?? 'DISABLED']}
            </Typography>
            <RealtimeTranscriptionFields
              idPrefix={`${prefix}-live`}
              value={realtime}
              onChange={(next) => {
                setRealtime(next)
                setLiveTestResult(null)
                if (next.live) fillBatch(next)
              }}
              errors={{
                protocol: errors['realtime.protocol'],
                url: errors['realtime.url'],
                model: errors['realtime.model'],
              }}
            >
              {prefill !== null && <Alert severity="info">{REALTIME_TEXTS.batchPrefilled}</Alert>}
              <RoleApiKeyField
                id={`${prefix}-live-api-key`}
                label={`${REALTIME_TEXTS.keyLabel} (optional)`}
                value={liveKey}
                onChange={setLiveKey}
                apiKeySet={settings.realtime?.apiKeySet ?? false}
                error={errors['realtime.apiKey']}
                emptyHint={`Wird verschlüsselt gespeichert und nie wieder angezeigt. ${REALTIME_TEXTS.keyHint}`}
              />
              <TextField
                id={`${prefix}-live-max-sessions`}
                label="Gleichzeitige Live-Sitzungen"
                type="number"
                value={maxSessions}
                onChange={(e) => setMaxSessions(e.target.value)}
                error={Boolean(errors['realtime.maxSessions'])}
                helperText={
                  errors['realtime.maxSessions'] ??
                  'Für die ganze Installation, 1 bis 100. Wer darüber diktiert, bekommt den Text nach dem Ende der Aufnahme. Ein eigener Server mit 16 GB GPU-Speicher trägt etwa eine Sitzung.'
                }
                size="small"
                sx={{ maxWidth: 320 }}
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
                  disabled={realtime.url.trim() === '' || realtime.model.trim() === ''}
                  onClick={() => void handleLiveTest()}
                >
                  Live-Dienst testen
                </BusyButton>
              </Box>
            </RealtimeTranscriptionFields>
          </Box>
        )}
        <Box>
          <BusyButton
            variant="contained"
            busy={saving}
            busyAnnouncement="Wird gespeichert"
            disabled={kind === 'transcription' && isVocabularyTooLong(vocabulary)}
            onClick={() => void handleSave()}
          >
            Speichern
          </BusyButton>
        </Box>
      </Stack>
    </Box>
  )
}
