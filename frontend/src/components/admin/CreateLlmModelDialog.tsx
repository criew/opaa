import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { testLlmModel } from '../../services/api'
import { useLlmModelStore } from '../../stores/llmModelStore'

const BASE_URL_HELP_TEXT =
  'Der OpenAI-kompatible Endpunkt der Modellschnittstelle. Auch lokal betriebene Modellserver ' +
  'bedienen diese Schnittstelle – etwa Ollama, mit angehängtem „/v1“.'

interface CreateLlmModelDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

/**
 * Creation form for a managed chat model (#759). No provider selector on purpose - the API only
 * ever speaks the OpenAI-compatible protocol (docs/features/llm-integration.md#anbietername-und-zieladresse-sind-zwei-verschiedene-dinge-gebaut),
 * so there is nothing to choose beyond address and credentials.
 */
export default function CreateLlmModelDialog({
  open,
  onClose,
  onCreated,
}: CreateLlmModelDialogProps) {
  const createNewModel = useLlmModelStore((s) => s.createNewModel)

  const [displayName, setDisplayName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [modelIdentifier, setModelIdentifier] = useState('')
  const [temperature, setTemperature] = useState('0.7')
  const [maxTokens, setMaxTokens] = useState('2000')
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [testing, setTesting] = useState(false)

  function resetAndClose() {
    if (submitting) return
    setDisplayName('')
    setBaseUrl('')
    setModelIdentifier('')
    setTemperature('0.7')
    setMaxTokens('2000')
    setApiKey('')
    setError(null)
    setTestResult(null)
    onClose()
  }

  function isValid() {
    return (
      displayName.trim() !== '' &&
      baseUrl.trim() !== '' &&
      modelIdentifier.trim() !== '' &&
      temperature.trim() !== '' &&
      maxTokens.trim() !== ''
    )
  }

  async function handleTest() {
    setTestResult(null)
    setTesting(true)
    try {
      const result = await testLlmModel({
        baseUrl: baseUrl.trim(),
        modelIdentifier: modelIdentifier.trim(),
        apiKey: apiKey.trim() === '' ? undefined : apiKey.trim(),
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

  async function handleCreate() {
    if (!isValid()) {
      setError(
        'Anzeigename, Basis-Adresse, Modell-Kennung, Temperatur und maximale Antwortlänge sind erforderlich.',
      )
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await createNewModel({
        displayName: displayName.trim(),
        baseUrl: baseUrl.trim(),
        modelIdentifier: modelIdentifier.trim(),
        temperature: Number(temperature),
        maxTokens: Number(maxTokens),
        apiKey: apiKey.trim() === '' ? undefined : apiKey.trim(),
      })
      resetAndClose()
      onCreated()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Modell konnte nicht angelegt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={resetAndClose} maxWidth="sm" fullWidth>
      <DialogTitle>Chat-Modell anlegen</DialogTitle>
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
            // eslint-disable-next-line jsx-a11y-x/no-autofocus
            autoFocus
            label="Anzeigename"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 120 } }}
            fullWidth
            size="small"
          />
          <TextField
            label="Basis-Adresse"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            helperText={BASE_URL_HELP_TEXT}
            placeholder="http://ollama:11434/v1"
            fullWidth
            size="small"
          />
          <TextField
            label="Modell-Kennung"
            value={modelIdentifier}
            onChange={(e) => setModelIdentifier(e.target.value)}
            placeholder="phi3:mini"
            fullWidth
            size="small"
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Temperatur"
              type="number"
              value={temperature}
              onChange={(e) => setTemperature(e.target.value)}
              slotProps={{ htmlInput: { min: 0, max: 2, step: 0.1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
            <TextField
              label="Maximale Antwortlänge (Token)"
              type="number"
              value={maxTokens}
              onChange={(e) => setMaxTokens(e.target.value)}
              slotProps={{ htmlInput: { min: 1 } }}
              size="small"
              sx={{ flex: 1 }}
            />
          </Stack>
          <TextField
            label="API-Schlüssel (optional)"
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            helperText="Lokale Endpunkte laufen häufig ohne Authentifizierung - leer lassen, wenn kein Schlüssel benötigt wird."
            fullWidth
            size="small"
            autoComplete="new-password"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button
          onClick={() => void handleTest()}
          disabled={testing || baseUrl.trim() === '' || modelIdentifier.trim() === ''}
        >
          {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
        </Button>
        <Button onClick={resetAndClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          onClick={() => void handleCreate()}
          variant="contained"
          disabled={submitting || !isValid()}
        >
          {submitting ? 'Wird angelegt …' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
