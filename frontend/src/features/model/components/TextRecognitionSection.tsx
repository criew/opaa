import { useEffect, useId, useState } from 'react'
import {
  secretPart,
  isVisionDraft,
  textRecognitionDraftOf,
  type TextRecognitionDraft,
} from '@/shared/ui/setup/setupDrafts'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SearchModelRoleState, TextRecognitionModelSettingsResponse } from '@/shared/api/types'
import ConnectionTestButton from '@/shared/ui/settings/ConnectionTestButton'
import SettingsForm from '@/shared/ui/settings/SettingsForm'
import StateDot from '@/shared/ui/StateDot'
import TextRecognitionFields from '@/shared/ui/setup/TextRecognitionFields'
import { TEXT_RECOGNITION_PURPOSE } from '@/shared/ui/textRecognition/textRecognitionTexts'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { notify } from '@/shared/stores/notificationStore'
import {
  getTextRecognitionModelSettings,
  testTextRecognitionModel,
  updateTextRecognitionModelSettings,
} from '../api/modelApi'
import { fieldErrorsOf } from './roleForm'
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
 * The text recognition (OCR) model role: checkbox and the fields of the assistant's step
 * (`TextRecognitionFields`), with a connection test in the stages „Adresse · Anmeldung ·
 * Erkennung". While the operation has not released text recognition, only a notice is shown.
 * The key is write-only, as for the other roles.
 */
export default function TextRecognitionSection() {
  const [settings, setSettings] = useState<TextRecognitionModelSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [enabled, setEnabled] = useState(false)
  const [draft, setDraft] = useState<TextRecognitionDraft>(() => textRecognitionDraftOf(null))
  // Remounts the fields after a load or save, so service and stored key follow the stored state.
  const [storedCount, setStoredCount] = useState(0)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<TextRecognitionTestView | null>(null)
  const [checking, setChecking] = useState(false)
  const catchUpHintId = useId()

  function apply(stored: TextRecognitionModelSettingsResponse) {
    setSettings(stored)
    setEnabled(stored.enabled)
    setDraft(textRecognitionDraftOf(stored))
    setStoredCount((count) => count + 1)
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

  /** A changed connection field makes the last test result stale and clears its own error. */
  function changeConnection(patch: Partial<TextRecognitionDraft>) {
    setDraft((current) => ({ ...current, ...patch }))
    setTestResult(null)
    setErrors((current) =>
      Object.fromEntries(Object.entries(current).filter(([field]) => !(field in patch))),
    )
  }

  const isVision = isVisionDraft(draft)
  const connection = {
    connectionType: draft.connectionType,
    baseUrl: draft.baseUrl.trim(),
    model: isVision ? draft.model.trim() : null,
    apiKey: secretPart(draft.apiKey.trim(), draft.removeKey),
  }

  async function handleSave() {
    setSaving(true)
    setErrors({})
    setError(null)
    try {
      const saved = await updateTextRecognitionModelSettings({ enabled, ...connection })
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
    const missing = {
      ...(draft.baseUrl.trim() === '' ? { baseUrl: 'Basis-Adresse fehlt' } : {}),
      ...(isVision && draft.model.trim() === '' ? { model: 'Modell-Kennung fehlt' } : {}),
    }
    if (Object.keys(missing).length > 0) {
      setErrors((current) => ({ ...current, ...missing }))
      return
    }
    setTesting(true)
    setTestResult(null)
    setError(null)
    try {
      const response = await testTextRecognitionModel(connection)
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

  const stored = textRecognitionDraftOf(settings)
  const dirty =
    enabled !== settings.enabled ||
    (Object.keys(stored) as (keyof TextRecognitionDraft)[]).some(
      (key) => draft[key] !== stored[key],
    )

  function discard() {
    apply(settings!)
    setErrors({})
    setError(null)
    setTestResult(null)
  }

  return (
    <Box sx={{ maxWidth: formWidth.column }}>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 2 }}>
        {INTRO}
      </Typography>
      <Typography role="status" sx={{ fontSize: fontSize.text, mb: 2 }}>
        Zustand: {checking ? 'Wird geprüft …' : STATE_TEXT[settings.state]}
      </Typography>
      <SettingsForm
        aria-label="Texterkennung"
        onSave={handleSave}
        busy={saving}
        dirty={dirty}
        onDiscard={discard}
        secondaryAction={<ConnectionTestButton busy={testing} onTest={() => void handleTest()} />}
        result={
          <Stack spacing={2}>
            <Box role="status">{testResult && <StagedTestResult result={testResult} />}</Box>
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        }
      >
        <Stack spacing={3}>
          <Box>
            <FormControlLabel
              control={
                <Checkbox
                  checked={enabled}
                  onChange={(e) => setEnabled(e.target.checked)}
                  slotProps={{ input: { 'aria-describedby': catchUpHintId } }}
                />
              }
              label="Texterkennung einschalten"
            />
            <Typography
              id={catchUpHintId}
              sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}
            >
              {CATCH_UP_HINT}
            </Typography>
          </Box>
          <TextRecognitionFields
            key={storedCount}
            value={draft}
            onChange={changeConnection}
            apiKeyStored={settings.apiKeySet}
            errors={errors}
          />
        </Stack>
      </SettingsForm>
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
