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
  getRerankModelSettings,
  getTranscriptionModelSettings,
  testRerankModel,
  testTranscriptionModel,
  updateRerankModelSettings,
  updateTranscriptionModelSettings,
} from '../api/modelApi'
import RoleApiKeyField from './RoleApiKeyField'
import { fieldErrorsOf, resolveApiKey, UNTOUCHED_API_KEY, type ApiKeyDraft } from './roleForm'

export type { ModelRoleKind }

/** How long after a save the state is read again; the probe runs on the server meanwhile. */
const RECHECK_DELAY_MS = 3000

/** What the section shows of a stored role; the rerank role has no language. */
type RoleSettings = Omit<
  TranscriptionModelSettingsResponse,
  'language' | 'vocabulary' | 'speechInputAvailable'
> & {
  language?: string
  vocabulary?: string
  speechInputAvailable?: boolean
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

  function apply(stored: RoleSettings) {
    setSettings(stored)
    setEnabled(stored.enabled)
    setBaseUrl(stored.baseUrl ?? '')
    setModel(stored.model ?? '')
    setLanguage(stored.language ?? '')
    setVocabulary(stored.vocabulary ?? '')
    setApiKey(UNTOUCHED_API_KEY)
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
    setSaving(true)
    setErrors({})
    setError(null)
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

  if (loadError) return <Alert severity="error">{loadError}</Alert>
  if (!settings) {
    return (
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        Einstellungen werden geladen …
      </Typography>
    )
  }

  const speechInputOff = kind === 'transcription' && settings.speechInputAvailable === false
  const prefix = `model-role-${kind}`

  return (
    <Box sx={{ maxWidth: '72ch' }}>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>{texts.intro}</Typography>
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
      <Typography role="status" sx={{ fontSize: 13, mb: 2 }}>
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
        <Stack direction="row" spacing={1}>
          <BusyButton
            variant="outlined"
            busy={testing}
            busyAnnouncement="Verbindung wird getestet"
            disabled={baseUrl.trim() === '' || model.trim() === ''}
            onClick={() => void handleTest()}
          >
            Verbindung testen
          </BusyButton>
          <BusyButton
            variant="contained"
            busy={saving}
            busyAnnouncement="Wird gespeichert"
            disabled={kind === 'transcription' && isVocabularyTooLong(vocabulary)}
            onClick={() => void handleSave()}
          >
            Speichern
          </BusyButton>
        </Stack>
      </Stack>
    </Box>
  )
}
