import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PageSection from '@/shared/ui/PageSection'
import KeyValueList from '@/shared/ui/KeyValueList'
import StatusLine from '@/shared/ui/StatusLine'
import {
  cancelEmbeddingReindex,
  getEmbeddingReindexStatus,
  startEmbeddingReindex,
} from '../api/searchAdminApi'
import type {
  EmbeddingIndexIdentity,
  EmbeddingReindexRunStatus,
  EmbeddingReindexStatusResponse,
} from '@/shared/api/types'

/** How often the section asks for the progress while a run is working. */
export const POLL_INTERVAL_MS = 5000

const RUN_STATUS_LABELS: Record<EmbeddingReindexRunStatus, string> = {
  RUNNING: 'läuft',
  CANCELLED: 'abgebrochen',
  FAILED: 'fehlgeschlagen',
  COMPLETED: 'abgeschlossen',
}

function describeModel(identity: EmbeddingIndexIdentity): string {
  const name = identity.model ?? 'unbekanntes Modell'
  return `${name} (${identity.dimensions} Dimensionen)`
}

function formatTimestamp(value?: string | null): string | null {
  if (!value) return null
  return new Date(value).toLocaleString('de-DE')
}

/**
 * The vector index against the installed embedding model and its runs: the re-embedding after a
 * model change and the refill after a discard. The model itself changes under Modelle →
 * Einbettung; here a run is tracked, stopped and resumed. While a run is working, the section
 * polls the progress regularly.
 */
export default function EmbeddingIndexSection() {
  const [status, setStatus] = useState<EmbeddingReindexStatusResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(
    () =>
      getEmbeddingReindexStatus()
        .then((loaded) => {
          setStatus(loaded)
          setError(null)
        })
        .catch((err: unknown) => {
          setError(
            err instanceof Error ? err.message : 'Der Zustand des Vektorindex ist nicht abrufbar.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  const running = status?.running ?? false
  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => void load(), POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [running, load])

  const act = async (action: () => Promise<EmbeddingReindexStatusResponse>) => {
    setBusy(true)
    try {
      setStatus(await action())
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Aktion ist fehlgeschlagen.')
    } finally {
      setBusy(false)
    }
  }

  if (!status) {
    return error ? (
      <Alert severity="error" sx={{ mb: 3 }}>
        {error}
      </Alert>
    ) : null
  }

  const percent =
    status.totalChunks > 0 ? Math.floor((status.embeddedChunks / status.totalChunks) * 100) : 100
  const lastRun = status.lastRun
  const resumable = status.stale && status.embeddedChunks > 0
  const refill = lastRun?.kind === 'REFILL'
  const refillPending =
    !status.stale &&
    refill &&
    lastRun.status !== 'COMPLETED' &&
    (status.withoutCurrentVector ?? 0) > 0

  return (
    <PageSection
      title="Vektorindex"
      description="Das Embedding-Modell wechselt unter Modelle → Einbettung. Nach einem Wechsel werden die vorhandenen Abschnitte neu eingebettet, ohne die Quellen erneut zu lesen; hier lässt sich der Lauf verfolgen, anhalten und fortsetzen."
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {status.stale ? (
        <StatusLine
          headline="Index veraltet"
          tone="warning"
          detail={
            <>
              Der Vektorindex stammt von {describeModel(status.current)}, konfiguriert ist{' '}
              {describeModel(status.configured)}. Bis die Neueinbettung abgeschlossen ist, ist die
              Vektorsuche ausgesetzt; Chat und Suche finden nur Abschnitte über die Volltextsuche.
            </>
          }
        />
      ) : (
        <StatusLine
          headline="Index aktuell"
          tone="success"
          detail={`Der Vektorindex passt zum konfigurierten Modell ${describeModel(status.configured)}.`}
        />
      )}

      {status.stale && (
        <Box sx={{ mb: 2 }}>
          <Typography variant="body2" sx={{ mb: 0.5 }}>
            {status.embeddedChunks.toLocaleString('de-DE')} von{' '}
            {status.totalChunks.toLocaleString('de-DE')} Abschnitten neu eingebettet ({percent} %)
            {!status.privateLibrariesCounted && ', ohne private Bibliotheken'}
          </Typography>
          <LinearProgress
            variant="determinate"
            value={percent}
            aria-label="Fortschritt der Neueinbettung"
          />
        </Box>
      )}

      {lastRun && (
        <KeyValueList
          sx={{ mb: 2 }}
          entries={[
            {
              label: refill ? 'Letztes Nachfüllen' : 'Letzter Lauf',
              value: RUN_STATUS_LABELS[lastRun.status],
            },
            {
              label: 'Gestartet',
              value: formatTimestamp(lastRun.startedAt),
              hidden: !lastRun.startedAt,
            },
            {
              label: 'Beendet',
              value: formatTimestamp(lastRun.finishedAt),
              hidden: !lastRun.finishedAt,
            },
            { label: 'Hinweis', value: lastRun.message, hidden: !lastRun.message },
          ]}
        />
      )}

      {refillPending && !running && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 2 }}>
          <Button
            variant="contained"
            disabled={busy}
            onClick={() => void act(startEmbeddingReindex)}
          >
            Nachfüllen fortsetzen
          </Button>
        </Stack>
      )}

      {refill && running && !status.stale && (
        <Stack direction="row" spacing={1.5} sx={{ mb: 2 }}>
          <Button
            variant="outlined"
            disabled={busy}
            onClick={() => void act(cancelEmbeddingReindex)}
          >
            Nachfüllen abbrechen
          </Button>
        </Stack>
      )}

      {status.stale && (
        <Stack direction="row" spacing={1.5}>
          {running ? (
            <Button
              variant="outlined"
              disabled={busy}
              onClick={() => void act(cancelEmbeddingReindex)}
            >
              Neueinbettung abbrechen
            </Button>
          ) : (
            <Button
              variant="contained"
              disabled={busy}
              onClick={() => void act(startEmbeddingReindex)}
            >
              {resumable ? 'Neueinbettung fortsetzen' : 'Neueinbettung starten'}
            </Button>
          )}
        </Stack>
      )}
    </PageSection>
  )
}
