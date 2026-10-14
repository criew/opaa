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
} from '../../services/indexingApi'
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
 * Der Vektorindex gegen das konfigurierte Embedding-Modell. Das Modell selbst steht in der
 * Konfiguration; hier wird nur die Neueinbettung gestartet, verfolgt und abgebrochen. Solange ein
 * Lauf arbeitet, fragt der Abschnitt den Fortschritt regelmäßig ab.
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

  return (
    <PageSection
      title="Vektorindex"
      description="Das Embedding-Modell legt die Konfiguration fest. Nach einem Wechsel werden die vorhandenen Abschnitte hier neu eingebettet, ohne die Quellen erneut zu lesen."
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
            { label: 'Letzter Lauf', value: RUN_STATUS_LABELS[lastRun.status] },
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
