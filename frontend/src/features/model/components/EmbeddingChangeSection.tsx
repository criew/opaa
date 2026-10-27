import { useState } from 'react'
import { Link as RouterLink } from 'react-router'
import { AxiosError } from 'axios'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  EmbeddingInfoResponse,
  EmbeddingModelDraft,
  EmbeddingModelProbeResponse,
  EmbeddingReindexStatusResponse,
} from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import {
  changeEmbeddingModelEndpoint,
  discardEmbeddingModelChange,
  probeEmbeddingModelChange,
  startEmbeddingModelChange,
} from '../api/modelApi'
import {
  formatBytes,
  formatCount,
  formatDuration,
  formatTokens,
  sameEndpoint,
} from './embeddingChange'

type KeyChoice = 'keep' | 'new' | 'none'

/** The field errors of a 400, by the field name after the prefix (`embeddingModel.baseUrl`). */
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

/** Whether the server refused the call for its rate limit. */
function isRateLimited(err: unknown): boolean {
  const cause = err instanceof Error ? err.cause : undefined
  return cause instanceof AxiosError && cause.response?.status === 429
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error ? err.message : fallback
}

interface EmbeddingChangeSectionProps {
  info: EmbeddingInfoResponse
  /** Reads the model and the index again after a change. */
  onChanged: () => void
}

/**
 * The guided change of the embedding model: a draft with probe embedding and preview, the
 * confirmation that starts the re-embedding, and while a change waits for its switch the discard
 * and the replacement endpoint. Without an installed model the same probe sets the first one. The key is write-only: „beibehalten" only for the same endpoint,
 * so a key never follows an address to another provider.
 */
export default function EmbeddingChangeSection({ info, onChanged }: EmbeddingChangeSectionProps) {
  if (!info.model) {
    return (
      <Box sx={{ maxWidth: '72ch' }}>
        <EmbeddingDraftForm info={info} mode="initial" onDone={onChanged} />
      </Box>
    )
  }
  const index = info.index ?? null
  if (index?.stale) {
    return <ChangeUnderWay info={info} index={index} onChanged={onChanged} />
  }
  return (
    <Box sx={{ maxWidth: '72ch' }}>
      {index?.lastRun?.kind === 'REFILL' && index.running && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Nach dem Verwerfen werden {formatCount(index.withoutCurrentVector ?? 0)} Abschnitte mit
          dem bisherigen Modell nachgefüllt. Bis dahin findet sie nur die Volltextsuche.
        </Alert>
      )}
      <EmbeddingDraftForm info={info} mode="change" onDone={onChanged} />
    </Box>
  )
}

interface ChangeUnderWayProps {
  info: EmbeddingInfoResponse
  index: EmbeddingReindexStatusResponse
  onChanged: () => void
}

function ChangeUnderWay({ info, index, onChanged }: ChangeUnderWayProps) {
  const [replacing, setReplacing] = useState(false)
  const [discarding, setDiscarding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const target = index.configured
  const previous = index.previous ?? null
  const percent =
    index.totalChunks > 0 ? Math.round((index.embeddedChunks / index.totalChunks) * 100) : 0

  async function handleDiscard() {
    const name = previous?.model ?? 'das bisherige Modell'
    const refill = index.withoutCurrentVector ?? 0
    const confirmed = await confirmAction({
      question: 'Wechsel des Embedding-Modells verwerfen?',
      consequence: (
        <>
          „{name}“ gilt wieder, und die Vektorsuche läuft sofort wieder.
          {refill > 0 &&
            ` ${formatCount(refill)} Abschnitte, die seit der Bestätigung geschrieben wurden, bettet OPAA danach mit „${name}“ nach; bis dahin findet sie nur die Volltextsuche.`}
        </>
      ),
      confirmLabel: 'Verwerfen',
      tone: 'caution',
    })
    if (!confirmed) return
    setDiscarding(true)
    setError(null)
    try {
      await discardEmbeddingModelChange()
      notify('Der Wechsel wurde verworfen; das bisherige Modell gilt wieder.', 'success')
      onChanged()
    } catch (err) {
      setError(messageOf(err, 'Verwerfen fehlgeschlagen'))
    } finally {
      setDiscarding(false)
    }
  }

  return (
    <Box sx={{ maxWidth: '72ch' }}>
      <Alert severity="warning" sx={{ mb: 2 }}>
        Die Vektorsuche ist ausgesetzt, bis die Neueinbettung abgeschlossen ist. Suche und Chat
        finden bis dahin nur über die Volltextsuche.
      </Alert>
      <Stack spacing={1} sx={{ mb: 2 }}>
        <Typography sx={{ fontSize: 13 }}>
          Neues Modell: <strong>{target.model}</strong> ({target.dimensions} Dimensionen)
        </Typography>
        {previous && (
          <Typography sx={{ fontSize: 13 }}>
            Bisheriges Modell: <strong>{previous.model}</strong> ({previous.dimensions} Dimensionen)
          </Typography>
        )}
        <Box>
          <LinearProgress
            variant="determinate"
            value={percent}
            aria-label="Fortschritt der Neueinbettung"
            sx={{ height: 6, borderRadius: 1 }}
          />
          <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
            {formatCount(index.embeddedChunks)} von {formatCount(index.totalChunks)} Abschnitten neu
            eingebettet
            {index.switching ? ' · Umschalten läuft' : index.running ? ' · läuft' : ' · angehalten'}
          </Typography>
        </Box>
        {index.lastRun?.message && !index.running && (
          <Typography sx={{ fontSize: 12.5 }}>{index.lastRun.message}</Typography>
        )}
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Anhalten und Fortsetzen unter{' '}
          <Link component={RouterLink} to="/admin/search/index">
            Suche &amp; Indexierung → Indexstatus
          </Link>
          . Nach dem Abschluss fallen die bisherigen Vektoren weg; zurück geht es dann nur mit einem
          neuen Wechsel.
        </Typography>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack direction="row" spacing={1} sx={{ mb: 2 }}>
        <BusyButton
          variant="outlined"
          color="warning"
          busy={discarding}
          busyAnnouncement="Wechsel wird verworfen"
          disabled={!previous || index.switching}
          onClick={() => void handleDiscard()}
        >
          Wechsel verwerfen
        </BusyButton>
        <Button
          variant="outlined"
          disabled={index.running}
          onClick={() => setReplacing((open) => !open)}
        >
          Ersatz-Endpunkt eintragen
        </Button>
      </Stack>
      {index.switching && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
          Der Vektorindex der neuen Spalte wird aufgebaut; verwerfen lässt sich der Wechsel jetzt
          nicht mehr.
        </Typography>
      )}
      {index.running && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
          Einen Ersatz-Endpunkt nimmt OPAA nur bei angehaltenem Lauf an.
        </Typography>
      )}
      {replacing && !index.running && (
        <EmbeddingDraftForm
          info={info}
          mode="replacement"
          fixedModel={target.model ?? info.model ?? undefined}
          onDone={() => {
            setReplacing(false)
            onChanged()
          }}
        />
      )}
    </Box>
  )
}

interface EmbeddingDraftFormProps {
  info: EmbeddingInfoResponse
  /**
   * A model change of the installed model, a replacement endpoint of the target model, or the
   * first model of an installation without one.
   */
  mode: 'change' | 'replacement' | 'initial'
  fixedModel?: string
  onDone: () => void
}

function EmbeddingDraftForm({ info, mode, fixedModel, onDone }: EmbeddingDraftFormProps) {
  const storedBaseUrl = info.baseUrl ?? ''
  const [baseUrl, setBaseUrl] = useState(storedBaseUrl)
  const [model, setModel] = useState(fixedModel ?? info.model ?? '')
  const [maxInputTokens, setMaxInputTokens] = useState(
    info.maxInputTokens ? String(info.maxInputTokens) : '',
  )
  const [keyChoice, setKeyChoice] = useState<KeyChoice>(info.apiKeySet ? 'keep' : 'none')
  const [keyInput, setKeyInput] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [probing, setProbing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<EmbeddingModelProbeResponse | null>(null)
  // Exhausted probes do not lock out the endpoint change: it has its own limit and probes itself.
  const [probesExhausted, setProbesExhausted] = useState(false)
  const offerEndpointChange =
    probesExhausted || (result?.succeeded === true && result.kind === 'ENDPOINT_CHANGE')
  const keepAllowed = Boolean(info.apiKeySet) && sameEndpoint(storedBaseUrl, baseUrl)
  const effectiveChoice: KeyChoice = keyChoice === 'keep' && !keepAllowed ? 'new' : keyChoice
  const prefix = `embedding-${mode}`

  function changed() {
    setResult(null)
    setError(null)
  }

  function draft(): EmbeddingModelDraft {
    const limit = Number.parseInt(maxInputTokens, 10)
    return {
      baseUrl: baseUrl.trim(),
      model: model.trim(),
      maxInputTokens: Number.isFinite(limit) ? limit : undefined,
      apiKey:
        effectiveChoice === 'keep' ? undefined : effectiveChoice === 'new' ? keyInput.trim() : '',
    }
  }

  async function handleProbe() {
    setProbing(true)
    setErrors({})
    setError(null)
    setResult(null)
    try {
      setResult(await probeEmbeddingModelChange(draft()))
      setProbesExhausted(false)
    } catch (err) {
      setProbesExhausted(isRateLimited(err))
      setErrors(fieldErrorsOf(err))
      setError(messageOf(err, 'Probeeinbettung fehlgeschlagen'))
    } finally {
      setProbing(false)
    }
  }

  async function handleEndpointChange() {
    setSaving(true)
    setError(null)
    try {
      await changeEmbeddingModelEndpoint(draft())
      notify('Der Endpunkt des Embedding-Modells wurde geändert.', 'success')
      onDone()
    } catch (err) {
      setErrors(fieldErrorsOf(err))
      setError(messageOf(err, 'Endpunkt ändern fehlgeschlagen'))
    } finally {
      setSaving(false)
    }
  }

  async function handleInstall(probed: EmbeddingModelProbeResponse) {
    const confirmed = await confirmAction({
      question: `„${model.trim()}“ als Embedding-Modell festlegen?`,
      consequence: (
        <>
          OPAA legt die Vektorablage mit {probed.dimensions} Dimensionen an; danach stehen
          Indexierung, Upload und Suche bereit. Ein späterer Wechsel des Modells bettet alle
          Abschnitte neu ein.
        </>
      ),
      confirmLabel: 'Festlegen',
      tone: 'caution',
    })
    if (!confirmed || probed.dimensions == null) return
    setSaving(true)
    setError(null)
    try {
      await startEmbeddingModelChange({ ...draft(), expectedDimensions: probed.dimensions })
      notify('Das Embedding-Modell ist festgelegt; Indexierung und Suche stehen bereit.', 'success')
      onDone()
    } catch (err) {
      setErrors(fieldErrorsOf(err))
      setError(messageOf(err, 'Festlegen fehlgeschlagen'))
    } finally {
      setSaving(false)
    }
  }

  async function handleStart(probed: EmbeddingModelProbeResponse) {
    const preview = probed.preview
    const confirmed = await confirmAction({
      question: `Embedding-Modell auf „${model.trim()}“ wechseln?`,
      consequence: (
        <>
          Ab dem Start bis zum Abschluss ist die Vektorsuche ausgesetzt; Suche und Chat finden nur
          über die Volltextsuche
          {preview ? `, ${formatDuration(preview.estimatedSeconds)} lang` : ''}. Nach dem Abschluss
          fallen die bisherigen Vektoren weg; zurück geht es dann nur mit einem neuen Wechsel.
        </>
      ),
      confirmLabel: 'Wechsel starten',
      tone: 'danger',
    })
    if (!confirmed || probed.dimensions == null) return
    setSaving(true)
    setError(null)
    try {
      await startEmbeddingModelChange({ ...draft(), expectedDimensions: probed.dimensions })
      notify('Der Wechsel wurde gestartet; die Neueinbettung läuft.', 'success')
      onDone()
    } catch (err) {
      setErrors(fieldErrorsOf(err))
      setError(messageOf(err, 'Wechsel fehlgeschlagen'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box>
      <Typography component="h3" sx={{ fontSize: 14, fontWeight: 600, mb: 1 }}>
        {mode === 'change'
          ? 'Modell wechseln oder Endpunkt ändern'
          : mode === 'initial'
            ? 'Embedding-Modell festlegen'
            : 'Ersatz-Endpunkt'}
      </Typography>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
        {mode === 'change'
          ? 'Die Probeeinbettung prüft den Endpunkt mit festen Probetexten. Gleicht er dem aktiven Modell, genügt ein Wechsel des Endpunkts ohne Neueinbettung; sonst zeigt OPAA, was die Neueinbettung kostet.'
          : mode === 'initial'
            ? 'Die Probeeinbettung prüft den Endpunkt und ermittelt die Zahl der Dimensionen; mit ihr legt OPAA die Vektorablage an. Es gibt noch keine Abschnitte, nichts wird neu eingebettet.'
            : 'Ein Ersatz-Endpunkt muss das neue Modell so einbetten wie bei der Bestätigung; danach den Lauf unter Indexstatus fortsetzen.'}
      </Typography>
      <Stack spacing={2}>
        <TextField
          id={`${prefix}-base-url`}
          label="Basis-Adresse"
          required
          value={baseUrl}
          onChange={(e) => {
            setBaseUrl(e.target.value)
            changed()
          }}
          error={Boolean(errors.baseUrl)}
          helperText={errors.baseUrl ?? 'Etwa http://ollama:11434/v1.'}
          size="small"
          fullWidth
        />
        <TextField
          id={`${prefix}-model`}
          label="Modell-Kennung"
          required
          value={model}
          disabled={mode === 'replacement'}
          onChange={(e) => {
            setModel(e.target.value)
            changed()
          }}
          error={Boolean(errors.model)}
          helperText={errors.model}
          size="small"
          fullWidth
        />
        <FormControl>
          <FormLabel id={`${prefix}-key-label`} sx={{ fontSize: 13 }}>
            Zugangsschlüssel
          </FormLabel>
          <RadioGroup
            row
            aria-labelledby={`${prefix}-key-label`}
            value={effectiveChoice}
            onChange={(e) => {
              setKeyChoice(e.target.value as KeyChoice)
              changed()
            }}
          >
            {info.apiKeySet && (
              <FormControlLabel
                value="keep"
                control={<Radio size="small" />}
                label="beibehalten"
                disabled={!keepAllowed}
              />
            )}
            <FormControlLabel value="new" control={<Radio size="small" />} label="neu" />
            <FormControlLabel value="none" control={<Radio size="small" />} label="ohne" />
          </RadioGroup>
          {info.apiKeySet && !keepAllowed && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              Die Adresse nennt einen anderen Endpunkt; der gespeicherte Schlüssel geht nicht mit.
            </Typography>
          )}
        </FormControl>
        {effectiveChoice === 'new' && (
          <TextField
            id={`${prefix}-api-key`}
            label="Neuer Zugangsschlüssel"
            type="password"
            value={keyInput}
            onChange={(e) => {
              setKeyInput(e.target.value)
              changed()
            }}
            error={Boolean(errors.apiKey)}
            helperText={errors.apiKey ?? 'Wird verschlüsselt gespeichert und nie wieder angezeigt.'}
            size="small"
            fullWidth
            autoComplete="new-password"
          />
        )}
        <TextField
          id={`${prefix}-max-input-tokens`}
          label="Eingabegrenze in Tokens"
          value={maxInputTokens}
          onChange={(e) => {
            setMaxInputTokens(e.target.value.replace(/\D/g, ''))
            changed()
          }}
          error={Boolean(errors.maxInputTokens)}
          helperText={
            errors.maxInputTokens ??
            'Leer für 2048, die Grenze eines unverändert gezogenen Ollama-Modells.'
          }
          size="small"
          sx={{ maxWidth: 260 }}
          slotProps={{ htmlInput: { inputMode: 'numeric' } }}
        />
        {error && <Alert severity="error">{error}</Alert>}
        {probesExhausted && (
          <Alert severity="info">
            Ohne Probeeinbettung lässt sich nur der Endpunkt ändern; OPAA prüft dabei selbst, ob er
            wie {mode === 'change' ? 'das aktive' : 'das neue'} Modell einbettet.
          </Alert>
        )}
        {result && !result.succeeded && <Alert severity="error">{result.message}</Alert>}
        {result?.succeeded && result.kind === 'ENDPOINT_CHANGE' && (
          <Alert severity="info">
            {result.message} Der Endpunkt bettet wie das aktive Modell ein
            {result.similarity != null
              ? ` (Ähnlichkeit ${result.similarity.toLocaleString('de-DE', { maximumFractionDigits: 4 })})`
              : ''}
            ; ein Wechsel des Endpunkts genügt, ohne Neueinbettung.
          </Alert>
        )}
        {result?.succeeded && result.kind === 'MODEL_CHANGE' && mode === 'replacement' && (
          <Alert severity="error">
            Der Endpunkt bettet anders ein als das neue Modell bei der Bestätigung; als
            Ersatz-Endpunkt taugt er nicht.
          </Alert>
        )}
        {result?.succeeded && result.kind === 'MODEL_CHANGE' && mode === 'change' && (
          <ChangePreview result={result} />
        )}
        {result?.succeeded && result.kind === 'INITIAL' && (
          <Alert severity="success">{result.message}</Alert>
        )}
        <Stack direction="row" spacing={1}>
          <BusyButton
            variant="outlined"
            busy={probing}
            busyAnnouncement="Probeeinbettung läuft"
            disabled={baseUrl.trim() === '' || model.trim() === ''}
            onClick={() => void handleProbe()}
          >
            Probeeinbettung
          </BusyButton>
          {offerEndpointChange && (
            <BusyButton
              variant="contained"
              busy={saving}
              busyAnnouncement="Endpunkt wird geändert"
              onClick={() => void handleEndpointChange()}
            >
              {mode === 'change' ? 'Endpunkt ändern' : 'Ersatz-Endpunkt speichern'}
            </BusyButton>
          )}
          {result?.succeeded && result.kind === 'INITIAL' && (
            <BusyButton
              variant="contained"
              busy={saving}
              busyAnnouncement="Embedding-Modell wird festgelegt"
              onClick={() => void handleInstall(result)}
            >
              Embedding-Modell festlegen
            </BusyButton>
          )}
          {result?.succeeded && result.kind === 'MODEL_CHANGE' && mode === 'change' && (
            <BusyButton
              variant="contained"
              color="error"
              busy={saving}
              busyAnnouncement="Wechsel wird gestartet"
              onClick={() => void handleStart(result)}
            >
              Wechsel starten
            </BusyButton>
          )}
        </Stack>
      </Stack>
    </Box>
  )
}

function ChangePreview({ result }: { result: EmbeddingModelProbeResponse }) {
  const preview = result.preview
  return (
    <Box>
      <Alert severity="success" sx={{ mb: 1.5 }}>
        {result.message}
      </Alert>
      {preview && (
        <Box
          component="dl"
          aria-label="Vorschau des Wechsels"
          sx={{
            display: 'grid',
            gridTemplateColumns: 'max-content 1fr',
            columnGap: 2,
            rowGap: 0.5,
            fontSize: 13,
            m: 0,
            mb: 1.5,
            '& dt': { color: 'text.secondary' },
            '& dd': { m: 0 },
          }}
        >
          <dt>Abschnitte</dt>
          <dd>
            {formatCount(preview.totalChunks)}
            {!preview.privateLibrariesCounted && ' (private Bibliotheken nicht ausgewiesen)'}
          </dd>
          <dt>Tokens</dt>
          <dd>{formatTokens(preview.estimatedTokens)}</dd>
          <dt>Dauer</dt>
          <dd>
            {formatDuration(preview.estimatedSeconds)}
            {preview.rateMeasured ? ' (gemessen am neuen Endpunkt)' : ' (aus der Voreinstellung)'}
          </dd>
          <dt>Speicher</dt>
          <dd>
            etwa {formatBytes(preview.storageLowerBytes)} bis{' '}
            {formatBytes(preview.storageUpperBytes)} zusätzlich (Vektortabelle heute{' '}
            {formatBytes(preview.currentTableBytes)})
          </dd>
        </Box>
      )}
      <Alert severity="warning" sx={{ mb: 1 }}>
        Ab dem Start bis zum Abschluss ist die Vektorsuche ausgesetzt. Suche und Chat finden nur
        über die Volltextsuche.
      </Alert>
      <Alert severity="warning">
        Nach dem Abschluss fallen die bisherigen Vektoren weg; zurück geht es dann nur mit einem
        neuen Wechsel. Den Platz der alten Vektoren gibt die Datenbank erst frei, wenn die Zeilen
        neu geschrieben werden.
      </Alert>
    </Box>
  )
}
