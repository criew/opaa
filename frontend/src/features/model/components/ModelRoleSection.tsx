import { useEffect, useState } from 'react'
import { AxiosError } from 'axios'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
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
import { notify } from '@/shared/stores/notificationStore'
import {
  getRerankModelSettings,
  getTranscriptionModelSettings,
  testRerankModel,
  testTranscriptionModel,
  updateRerankModelSettings,
  updateTranscriptionModelSettings,
} from '../api/modelApi'

export type ModelRoleKind = 'rerank' | 'transcription'

/** What the section shows of a stored role; the rerank role has no language. */
type RoleSettings = Omit<
  TranscriptionModelSettingsResponse,
  'language' | 'speechInputAvailable'
> & {
  language?: string
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
    intro:
      'Das Rerank-Modell ordnet die Kandidaten jeder Suche neu, bevor die Antwort entsteht. Ab Werk ist es aus; ohne Rerank-Modell sucht OPAA ohne diese Stufe weiter.',
    addressHint: 'Ohne den Pfad /rerank, etwa http://reranker:80.',
  },
  transcription: {
    switchLabel: 'Spracheingabe einschalten',
    intro:
      'Das Transkriptionsmodell wandelt diktierte Fragen im Chat in Text. Ab Werk ist es aus; das Mikrofon erscheint erst, wenn das Modell antwortet.',
    addressHint: 'Ohne den Pfad /audio/transcriptions, etwa http://speaches:8000/v1.',
  },
}

const STATE_TEXT: Record<SearchModelRoleState, string> = {
  DISABLED: 'Ausgeschaltet.',
  ACTIVE: 'Eingeschaltet, der Endpunkt antwortet.',
  UNCONFIGURED: 'Eingeschaltet, aber Basis-Adresse oder Modell fehlen.',
  UNREACHABLE: 'Eingeschaltet, aber der Endpunkt antwortet noch nicht.',
}

/** The field errors of a 400, by the field name after the role prefix (`rerank.baseUrl`). */
function fieldErrorsOf(err: unknown): Record<string, string> {
  const cause = err instanceof Error ? err.cause : undefined
  if (!(cause instanceof AxiosError)) return {}
  const data = cause.response?.data as
    { fieldErrors?: { field: string; message: string }[] } | undefined
  const result: Record<string, string> = {}
  for (const error of data?.fieldErrors ?? []) {
    const field = error.field.substring(error.field.indexOf('.') + 1)
    result[field] ??= error.message
  }
  return result
}

function load(kind: ModelRoleKind): Promise<RoleSettings> {
  return kind === 'rerank' ? getRerankModelSettings() : getTranscriptionModelSettings()
}

/**
 * The rerank or the transcription model role in one form: switch, endpoint, key and — for
 * dictation — language, with a connection test and the role's last known state. The key is
 * write-only, as for the chat models: an untouched field keeps it, „entfernen" removes it.
 */
export default function ModelRoleSection({ kind }: { kind: ModelRoleKind }) {
  const texts = TEXTS[kind]
  const [settings, setSettings] = useState<RoleSettings | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [language, setLanguage] = useState('')
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [apiKeyTouched, setApiKeyTouched] = useState(false)
  const [apiKeyClearRequested, setApiKeyClearRequested] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<LlmModelTestResponse | null>(null)

  function apply(stored: RoleSettings) {
    setSettings(stored)
    setEnabled(stored.enabled)
    setBaseUrl(stored.baseUrl ?? '')
    setModel(stored.model ?? '')
    setLanguage(stored.language ?? '')
    setApiKeyInput('')
    setApiKeyTouched(false)
    setApiKeyClearRequested(false)
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

  /** Omitted keeps the stored key, empty removes it, anything else replaces it. */
  function resolveApiKey(): string | undefined {
    if (apiKeyClearRequested) return ''
    if (apiKeyTouched) return apiKeyInput.trim()
    return undefined
  }

  async function handleSave() {
    setSaving(true)
    setErrors({})
    setError(null)
    const common = {
      enabled,
      baseUrl: baseUrl.trim(),
      model: model.trim(),
      apiKey: resolveApiKey(),
    }
    try {
      const saved =
        kind === 'rerank'
          ? await updateRerankModelSettings(common)
          : await updateTranscriptionModelSettings({ ...common, language: language.trim() })
      apply(saved)
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
    const request = { baseUrl: baseUrl.trim(), model: model.trim(), apiKey: resolveApiKey() }
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
        Zustand: {STATE_TEXT[settings.state]}
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
        <Box>
          <TextField
            id={`${prefix}-api-key`}
            label="Zugangsschlüssel (optional)"
            type="password"
            value={apiKeyInput}
            disabled={apiKeyClearRequested}
            onChange={(e) => {
              setApiKeyTouched(true)
              setApiKeyClearRequested(false)
              setApiKeyInput(e.target.value)
            }}
            error={Boolean(errors.apiKey)}
            helperText={
              errors.apiKey ??
              (apiKeyClearRequested
                ? 'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
                : settings.apiKeySet
                  ? 'Leer lassen, um den gespeicherten Schlüssel unverändert zu lassen.'
                  : 'Wird verschlüsselt gespeichert und nie wieder angezeigt.')
            }
            size="small"
            fullWidth
            autoComplete="new-password"
          />
          {settings.apiKeySet && (
            <Button
              size="small"
              color={apiKeyClearRequested ? 'primary' : 'error'}
              sx={{ mt: 0.5 }}
              onClick={() => {
                setApiKeyClearRequested(!apiKeyClearRequested)
                setApiKeyTouched(false)
                setApiKeyInput('')
              }}
            >
              {apiKeyClearRequested
                ? 'Entfernen rückgängig machen'
                : 'Gespeicherten Schlüssel entfernen'}
            </Button>
          )}
        </Box>
        {kind === 'transcription' && (
          <TextField
            id={`${prefix}-language`}
            label="Sprache"
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            error={Boolean(errors.language)}
            helperText={
              errors.language ??
              'Kürzel aus zwei Buchstaben, etwa de; leer erkennt das Modell die Sprache selbst.'
            }
            size="small"
            sx={{ maxWidth: 240 }}
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
            onClick={() => void handleSave()}
          >
            Speichern
          </BusyButton>
        </Stack>
      </Stack>
    </Box>
  )
}
