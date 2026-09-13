import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { LlmModelResponse } from '../../../types/api'
import { testLlmModel } from '../../../services/api'
import { useLlmModelStore } from '../../../stores/llmModelStore'

export const BASE_URL_HELP_TEXT =
  'Der OpenAI-kompatible Endpunkt der Modellschnittstelle. Auch lokal betriebene Modellserver ' +
  'bedienen diese Schnittstelle – etwa Ollama, mit angehängtem „/v1“.'

export const REQUIRED_FIELDS_HINT =
  'Anzeigename, Basis-Adresse, Modell-Kennung, Temperatur und maximale Antwortlänge sind ' +
  'erforderlich.'

interface LlmModelFormDialogProps {
  open: boolean
  /** The model being edited; `null` creates a new one. */
  model: LlmModelResponse | null
  onClose: () => void
  onSaved: (model: LlmModelResponse | null) => void
}

const NEW_DRAFT = {
  displayName: '',
  baseUrl: '',
  modelIdentifier: '',
  temperature: '0.7',
  maxTokens: '2000',
}

function draftFrom(model: LlmModelResponse | null) {
  if (!model) return NEW_DRAFT
  return {
    displayName: model.displayName,
    baseUrl: model.baseUrl,
    modelIdentifier: model.modelIdentifier,
    temperature: String(model.temperature),
    maxTokens: String(model.maxTokens),
  }
}

/**
 * Das Formular eines Chat-Modells (#759, #1621) — dasselbe für Anlegen und Bearbeiten, wie
 * `UserFormDialog` unter „Benutzer".
 *
 * Kein Anbieter-Auswahlfeld: Die Schnittstelle spricht ausschließlich das OpenAI-kompatible
 * Protokoll, es gibt also nichts zu wählen außer Adresse und Zugangsdaten.
 *
 * Der Aufrufer gibt dem Dialog einen `key`, damit ein Wechsel des Modells den Entwurf neu setzt —
 * MUI unmountet beim Schließen nur die Kinder, nicht die Komponente.
 */
export default function LlmModelFormDialog({
  open,
  model,
  onClose,
  onSaved,
}: LlmModelFormDialogProps) {
  const createNewModel = useLlmModelStore((s) => s.createNewModel)
  const updateExistingModel = useLlmModelStore((s) => s.updateExistingModel)

  const [draft, setDraft] = useState(() => draftFrom(model))
  const [apiKeyInput, setApiKeyInput] = useState('')
  const [apiKeyTouched, setApiKeyTouched] = useState(false)
  // #759 review: eine ausdrückliche Anforderung statt „leeres Feld heißt löschen" - das Feld
  // startet bei jedem Modell leer, weil der Schlüssel nie zurückgelesen wird; unberührt-leer und
  // „bitte entfernen" wären sonst nicht zu unterscheiden.
  const [apiKeyClearRequested, setApiKeyClearRequested] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [testing, setTesting] = useState(false)

  const isEdit = model !== null
  const titel = isEdit ? `„${model.displayName}“ bearbeiten` : 'Chat-Modell anlegen'
  const requiredHintId = 'llm-model-form-required-hint'

  function isValid() {
    return (
      draft.displayName.trim() !== '' &&
      draft.baseUrl.trim() !== '' &&
      draft.modelIdentifier.trim() !== '' &&
      draft.temperature.trim() !== '' &&
      draft.maxTokens.trim() !== ''
    )
  }

  /**
   * Löst auf, was für `apiKey` zu senden ist — die Dreiwege-Konvention von `LlmModelRequest`:
   * weggelassen heißt unverändert, leerer Text heißt entfernen, alles andere heißt setzen.
   *
   * Der Verbindungstest bekommt denselben Wert, obwohl der leere Text dort etwas anderes
   * bedeutet: Er speichert nie etwas, und ein weggelassener Schlüssel lässt ihn den **gespeicherten**
   * Schlüssel dieses Modells weiterverwenden. Genau das soll auch mitten im Bearbeiten gelten.
   */
  function resolveApiKey(): string | undefined {
    if (apiKeyClearRequested) return ''
    if (apiKeyTouched) return apiKeyInput.trim()
    return undefined
  }

  function close() {
    if (submitting) return
    onClose()
  }

  async function handleTest() {
    setTestResult(null)
    setTesting(true)
    try {
      const result = await testLlmModel({
        baseUrl: draft.baseUrl.trim(),
        modelIdentifier: draft.modelIdentifier.trim(),
        apiKey: resolveApiKey(),
        modelId: model?.id,
      })
      setTestResult(result)
    } catch (err) {
      setTestResult({
        success: false,
        message: err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen',
      })
    } finally {
      setTesting(false)
    }
  }

  async function handleSubmit() {
    if (!isValid()) {
      setError(REQUIRED_FIELDS_HINT)
      return
    }
    setError(null)
    setSubmitting(true)
    const request = {
      displayName: draft.displayName.trim(),
      baseUrl: draft.baseUrl.trim(),
      modelIdentifier: draft.modelIdentifier.trim(),
      temperature: Number(draft.temperature),
      maxTokens: Number(draft.maxTokens),
      apiKey: resolveApiKey(),
    }
    try {
      if (model) {
        const updated = await updateExistingModel(model.id, request)
        onSaved(updated)
      } else {
        await createNewModel(request)
        onSaved(null)
      }
      onClose()
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : isEdit
            ? 'Aktualisierung fehlgeschlagen'
            : 'Modell konnte nicht angelegt werden',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
      <DialogTitle>{titel}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        {testResult && (
          <Alert severity={testResult.success ? 'success' : 'error'} sx={{ mb: 2 }}>
            {testResult.message}
          </Alert>
        )}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Anzeigename"
            required
            value={draft.displayName}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
            size="small"
            fullWidth
          />
          <TextField
            label="Basis-Adresse"
            required
            value={draft.baseUrl}
            onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
            helperText={BASE_URL_HELP_TEXT}
            size="small"
            fullWidth
          />
          <TextField
            label="Modell-Kennung"
            required
            value={draft.modelIdentifier}
            onChange={(e) => setDraft({ ...draft, modelIdentifier: e.target.value })}
            size="small"
            fullWidth
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Temperatur"
              required
              type="number"
              value={draft.temperature}
              onChange={(e) => setDraft({ ...draft, temperature: e.target.value })}
              slotProps={{ htmlInput: { min: 0, max: 2, step: 0.1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Maximale Antwortlänge (Token)"
              required
              type="number"
              value={draft.maxTokens}
              onChange={(e) => setDraft({ ...draft, maxTokens: e.target.value })}
              slotProps={{ htmlInput: { min: 1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
          </Stack>
          <TextField
            label="API-Schlüssel (optional)"
            type="password"
            value={apiKeyInput}
            disabled={apiKeyClearRequested}
            onChange={(e) => {
              setApiKeyTouched(true)
              setApiKeyClearRequested(false)
              setApiKeyInput(e.target.value)
            }}
            helperText={
              apiKeyClearRequested
                ? 'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
                : isEdit
                  ? 'Leer lassen, um den gespeicherten Schlüssel unverändert zu lassen.'
                  : undefined
            }
            size="small"
            fullWidth
            autoComplete="new-password"
          />
          {model?.apiKeySet && (
            <Box>
              {apiKeyClearRequested ? (
                <Button size="small" onClick={() => setApiKeyClearRequested(false)}>
                  Entfernen rückgängig machen
                </Button>
              ) : (
                <Button
                  size="small"
                  color="error"
                  onClick={() => {
                    setApiKeyClearRequested(true)
                    setApiKeyTouched(false)
                    setApiKeyInput('')
                  }}
                  aria-label={`Gespeicherten Schlüssel von "${model.displayName}" entfernen`}
                >
                  Gespeicherten Schlüssel entfernen
                </Button>
              )}
            </Box>
          )}
          {!isValid() && (
            <Typography id={requiredHintId} variant="caption" sx={{ color: 'text.secondary' }}>
              {REQUIRED_FIELDS_HINT}
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button
          onClick={() => void handleTest()}
          disabled={testing || draft.baseUrl.trim() === '' || draft.modelIdentifier.trim() === ''}
        >
          {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
        </Button>
        <Box sx={{ flex: 1 }} />
        <Button onClick={close} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || !isValid()}
          aria-describedby={!isValid() ? requiredHintId : undefined}
        >
          {isEdit ? 'Speichern' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
