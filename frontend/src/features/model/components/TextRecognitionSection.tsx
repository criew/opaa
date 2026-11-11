import { useEffect, useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type {
  SearchModelRoleState,
  TextRecognitionConnectionType,
  TextRecognitionModelSettingsResponse,
} from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import StateDot from '@/shared/ui/StateDot'
import {
  TEXT_RECOGNITION_CONNECTION_TYPE_ORDER as CONNECTION_TYPE_ORDER,
  TEXT_RECOGNITION_CONNECTION_TYPES as CONNECTION_TYPES,
  TEXT_RECOGNITION_PURPOSE,
  TEXT_RECOGNITION_VISION_NOTICE as VISION_NOTICE,
} from '@/shared/ui/textRecognition/textRecognitionTexts'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { notify } from '@/shared/stores/notificationStore'
import {
  getTextRecognitionModelSettings,
  testTextRecognitionModel,
  updateTextRecognitionModelSettings,
} from '../api/modelApi'
import RoleApiKeyField from './RoleApiKeyField'
import { fieldErrorsOf, resolveApiKey, UNTOUCHED_API_KEY, type ApiKeyDraft } from './roleForm'
import {
  failedStage,
  stageStates,
  TEST_STAGE_LABELS,
  TEST_STAGES,
  type TestStageState,
  type TextRecognitionTestView,
} from './textRecognitionStages'
import { fontSize, formWidth } from '@/shared/theme/tokens'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

/** How long after a save the state is read again; the probe runs on the server meanwhile. */
const RECHECK_DELAY_MS = 3000

const INTRO = `${TEXT_RECOGNITION_PURPOSE} Ab Werk ist sie aus.`

const CATCH_UP_HINT = 'Bestehende Scans werden nach dem Einschalten automatisch nachverarbeitet.'

const STATE_TEXT: Record<SearchModelRoleState, string> = {
  DISABLED: 'Ausgeschaltet.',
  ACTIVE: 'Eingeschaltet, der Dienst antwortet.',
  UNCONFIGURED: 'Eingeschaltet, aber Basis-Adresse oder Modell fehlen.',
  UNREACHABLE: 'Eingeschaltet, aber der Dienst antwortet noch nicht.',
}

const STAGE_STATE: Record<TestStageState, { tone: StatusTone; word: string }> = {
  passed: { tone: 'success', word: 'in Ordnung' },
  failed: { tone: 'error', word: 'fehlgeschlagen' },
  skipped: { tone: 'neutral', word: 'nicht geprüft' },
}

/**
 * The text recognition (OCR) model role: switch, connection type, endpoint, key and — for a
 * vision model — the model, with a connection test in the stages „Adresse · Anmeldung ·
 * Erkennung". While the operation has not released text recognition, only a notice is shown.
 * The key is write-only, as for the other roles.
 */
export default function TextRecognitionSection() {
  const [settings, setSettings] = useState<TextRecognitionModelSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [enabled, setEnabled] = useState(false)
  const [connectionType, setConnectionType] =
    useState<TextRecognitionConnectionType>('DOCLING_SERVE')
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [apiKey, setApiKey] = useState<ApiKeyDraft>(UNTOUCHED_API_KEY)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<TextRecognitionTestView | null>(null)
  const [checking, setChecking] = useState(false)
  const connectionLabelId = useId()
  const catchUpHintId = useId()

  function apply(stored: TextRecognitionModelSettingsResponse) {
    setSettings(stored)
    setEnabled(stored.enabled)
    setConnectionType(stored.connectionType)
    setBaseUrl(stored.baseUrl ?? '')
    setModel(stored.model ?? '')
    setApiKey(UNTOUCHED_API_KEY)
  }

  useEffect(() => {
    let current = true
    getTextRecognitionModelSettings()
      .then((stored) => current && apply(stored))
      .catch((err: unknown) => {
        if (current) setLoadError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      current = false
    }
  }, [attempt])

  useEffect(() => {
    if (!checking) return
    let current = true
    const timer = setTimeout(() => {
      getTextRecognitionModelSettings()
        .then((stored) => current && setSettings(stored))
        .catch(() => undefined)
        .finally(() => current && setChecking(false))
    }, RECHECK_DELAY_MS)
    return () => {
      current = false
      clearTimeout(timer)
    }
  }, [checking])

  /** A changed connection field makes the last test result stale. */
  function changeConnection(update: () => void, field: string) {
    update()
    setTestResult(null)
    setErrors((current) => {
      const next = { ...current }
      delete next[field]
      return next
    })
  }

  const isVision = connectionType === 'OPENAI_VISION'

  async function handleSave() {
    setSaving(true)
    setErrors({})
    setError(null)
    try {
      const saved = await updateTextRecognitionModelSettings({
        enabled,
        connectionType,
        baseUrl: baseUrl.trim(),
        model: isVision ? model.trim() : null,
        apiKey: resolveApiKey(apiKey),
      })
      apply(saved)
      setChecking(saved.enabled && saved.state !== 'ACTIVE')
      notify('Die Texterkennung wurde gespeichert.', 'success')
    } catch (err) {
      const fields = fieldErrorsOf(err)
      setErrors(fields)
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function handleTest() {
    setTesting(true)
    setTestResult(null)
    setError(null)
    try {
      const response = await testTextRecognitionModel({
        connectionType,
        baseUrl: baseUrl.trim(),
        model: isVision ? model.trim() : null,
        apiKey: resolveApiKey(apiKey),
      })
      setTestResult({ kind: 'outcome', response })
    } catch (err) {
      const fields = fieldErrorsOf(err)
      setErrors(fields)
      if (fields.baseUrl) {
        setTestResult({ kind: 'addressRefused', message: fields.baseUrl })
      } else if (Object.keys(fields).length === 0 || fields.enabled) {
        setError(err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen')
      }
    } finally {
      setTesting(false)
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

  if (!settings.textRecognitionAvailable) {
    return (
      <Box sx={{ maxWidth: formWidth.column }}>
        <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 2 }}>
          {INTRO}
        </Typography>
        <Alert severity="info">
          Der Betrieb hat die Texterkennung nicht freigegeben (OPAA_OCR_ENABLED). Gescannte PDFs
          ohne Textebene werden weiter abgewiesen.
          {settings.baseUrl ? ' Die gespeicherte Anbindung bleibt erhalten.' : ''}
        </Alert>
      </Box>
    )
  }

  const type = CONNECTION_TYPES[connectionType]

  return (
    <Box sx={{ maxWidth: formWidth.column }}>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 2 }}>
        {INTRO}
      </Typography>
      <Typography role="status" sx={{ fontSize: fontSize.text, mb: 2 }}>
        Zustand: {checking ? 'Wird geprüft …' : STATE_TEXT[settings.state]}
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={2}>
        <Box>
          <FormControlLabel
            control={
              <Switch
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                slotProps={{ input: { 'aria-describedby': catchUpHintId } }}
              />
            }
            label="Texterkennung einschalten"
          />
          <Typography id={catchUpHintId} sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
            {CATCH_UP_HINT}
          </Typography>
        </Box>
        <FormControl>
          <FormLabel id={connectionLabelId} sx={{ fontSize: fontSize.meta, mb: 0.5 }}>
            Anbindung
          </FormLabel>
          <RadioGroup
            aria-labelledby={connectionLabelId}
            value={connectionType}
            onChange={(e) =>
              changeConnection(
                () => setConnectionType(e.target.value as TextRecognitionConnectionType),
                'connectionType',
              )
            }
          >
            {CONNECTION_TYPE_ORDER.map((key) => (
              <FormControlLabel
                key={key}
                value={key}
                control={<Radio size="small" />}
                sx={{ alignItems: 'flex-start', mb: 0.5, '& .MuiRadio-root': { pt: 0.25 } }}
                label={
                  <Box>
                    <Typography sx={{ fontSize: fontSize.text, fontWeight: 500 }}>
                      {CONNECTION_TYPES[key].label}
                    </Typography>
                    <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
                      {CONNECTION_TYPES[key].description}
                    </Typography>
                  </Box>
                }
              />
            ))}
          </RadioGroup>
        </FormControl>
        {isVision && <Alert severity="info">{VISION_NOTICE}</Alert>}
        <FormField
          id="model-role-text-recognition-base-url"
          label="Basis-Adresse"
          required={enabled}
          value={baseUrl}
          onChange={(e) => changeConnection(() => setBaseUrl(e.target.value), 'baseUrl')}
          error={Boolean(errors.baseUrl)}
          helperText={errors.baseUrl ?? type.addressHint}
          size="small"
          fullWidth
        />
        {isVision && (
          <FormField
            id="model-role-text-recognition-model"
            label="Modell-Kennung"
            required={enabled}
            value={model}
            onChange={(e) => changeConnection(() => setModel(e.target.value), 'model')}
            error={Boolean(errors.model)}
            helperText={errors.model}
            size="small"
            fullWidth
          />
        )}
        <RoleApiKeyField
          id="model-role-text-recognition-api-key"
          value={apiKey}
          onChange={(next) => changeConnection(() => setApiKey(next), 'apiKey')}
          apiKeySet={settings.apiKeySet}
          error={errors.apiKey}
        />
        <Box role="status">{testResult && <StagedTestResult result={testResult} />}</Box>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
          <BusyButton
            variant="outlined"
            busy={testing}
            busyAnnouncement="Verbindung wird getestet"
            disabled={baseUrl.trim() === '' || (isVision && model.trim() === '')}
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

/**
 * The test result as three stages, each with its state in words; the message stands under the
 * stage that failed, or under the list when every stage passed.
 */
function StagedTestResult({ result }: { result: TextRecognitionTestView }) {
  const states = stageStates(result)
  const failed = failedStage(result)
  const message = result.kind === 'addressRefused' ? result.message : result.response.message
  return (
    <Box
      component="section"
      aria-label="Ergebnis des Verbindungstests"
      sx={{ borderTop: 1, borderColor: 'divider', pt: 1.5 }}
    >
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {TEST_STAGES.map((stage) => {
          const state = STAGE_STATE[states[stage]]
          return (
            <Box component="li" key={stage} sx={{ mb: 0.75 }}>
              <StateDot tone={state.tone} label={`${TEST_STAGE_LABELS[stage]}: ${state.word}`} />
              {stage === failed && (
                <Typography sx={{ fontSize: fontSize.meta, pl: 2, mt: 0.25 }}>{message}</Typography>
              )}
            </Box>
          )
        })}
      </Box>
      {failed === null && (
        <Typography sx={{ fontSize: fontSize.meta, mt: 0.5 }}>{message}</Typography>
      )}
    </Box>
  )
}
