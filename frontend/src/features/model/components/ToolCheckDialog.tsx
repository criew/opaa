import { useEffect, useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import LinearProgress from '@mui/material/LinearProgress'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import type {
  LlmModelResponse,
  LlmModelToolCheckResponse,
  ToolCapability,
} from '@/shared/api/types'
import { fontFamily, fontSize } from '@/shared/theme/tokens'
import {
  getLatestLlmModelToolCheck,
  getLlmModelToolCheckReport,
  startLlmModelToolCheck,
} from '../api/modelApi'
import {
  TOOL_CHECK_CATEGORY_LABELS,
  TOOL_CHECK_FLOOR,
  TOOL_CHECK_REPETITIONS,
  toolCheckProposal,
  toolCheckReportFileName,
} from './toolCheck'

/** How often a running check is read again. */
export const TOOL_CHECK_POLL_MS = 3000

interface ToolCheckDialogProps {
  model: LlmModelResponse
  open: boolean
  onClose: () => void
  /** Opens the capability dialog with the check's proposal. */
  onAdopt: (proposal: { value: ToolCapability; measurementRun: string }) => void
}

/**
 * Runs the tool-calling case base against this model and shows the result per category. The check
 * runs on the server: closing the dialog does not stop it, reopening shows its progress. It never
 * records the capability itself; „Ergebnis übernehmen“ opens the capability dialog prefilled.
 */
export default function ToolCheckDialog({ model, open, onClose, onAdopt }: ToolCheckDialogProps) {
  const [check, setCheck] = useState<LlmModelToolCheckResponse | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [repetitions, setRepetitions] = useState(1)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const titleId = useId()
  const narrow = useMediaQuery(useTheme().breakpoints.down('sm'))
  const repetitionsId = useId()

  // a tick re-reads the check: once on opening, then every TOOL_CHECK_POLL_MS while it runs
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!open) return
    let cancelled = false
    getLatestLlmModelToolCheck(model.id)
      .then((latest) => {
        if (cancelled) return
        setCheck(latest)
        setError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Die Prüfung konnte nicht gelesen werden.')
        }
      })
      .finally(() => {
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [open, model.id, tick])

  const running = check?.status === 'RUNNING'
  useEffect(() => {
    if (!open || !running) return
    const timer = setInterval(() => setTick((value) => value + 1), TOOL_CHECK_POLL_MS)
    return () => clearInterval(timer)
  }, [open, running])

  async function start() {
    setStarting(true)
    setError(null)
    try {
      setCheck(await startLlmModelToolCheck(model.id, repetitions))
    } catch (err) {
      setError(
        err instanceof Error && err.message.includes('409')
          ? 'Es läuft bereits eine Werkzeugprüfung. Warten Sie, bis sie beendet ist.'
          : err instanceof Error
            ? err.message
            : 'Die Prüfung konnte nicht gestartet werden.',
      )
    } finally {
      setStarting(false)
    }
  }

  async function download(finished: LlmModelToolCheckResponse) {
    try {
      const markdown = await getLlmModelToolCheckReport(model.id, finished.id)
      const url = URL.createObjectURL(new Blob([markdown], { type: 'text/markdown' }))
      const link = document.createElement('a')
      link.href = url
      link.download = toolCheckReportFileName(finished)
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Der Bericht konnte nicht geladen werden.')
    }
  }

  const proposal = toolCheckProposal(check)
  const canStart = loaded && !running

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="sm"
      fullWidth
      fullScreen={narrow}
      aria-labelledby={titleId}
    >
      <DialogTitle id={titleId}>Werkzeuge prüfen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Typography sx={{ fontSize: fontSize.text, mb: 1.5 }}>
          Die Prüfung stellt dem Modell „{model.displayName}“ (
          <Box component="span" sx={{ fontFamily: fontFamily.mono, overflowWrap: 'anywhere' }}>
            {model.modelIdentifier}
          </Box>
          ) erfundene Fragen mit dem Suchwerkzeug der Agenten und bewertet, ob es das Werkzeug
          richtig aufruft. Sie misst nur und ändert die Werkzeugfähigkeit nicht. Sie dauert je nach
          Modell einige Minuten und läuft weiter, wenn Sie den Dialog schließen.
        </Typography>

        {running && check && <Progress check={check} />}
        {check && check.status === 'COMPLETED' && <Result check={check} />}
        {check && check.status === 'FAILED' && (
          <Alert severity="error" sx={{ mb: 2 }}>
            Die letzte Prüfung ist gescheitert: {check.failure}
          </Alert>
        )}
        {check && check.status === 'ABORTED' && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Die letzte Prüfung wurde abgebrochen, weil der Server neu gestartet wurde. Starten Sie
            sie erneut.
          </Alert>
        )}
        {check && check.status === 'COMPLETED' && !check.currentCombination && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Das Ergebnis gilt für eine frühere Adresse oder Modell-Kennung und lässt sich nicht
            übernehmen. Prüfen Sie erneut.
          </Alert>
        )}

        {canStart && (
          <FormControl sx={{ display: 'block', mt: 1 }}>
            <FormLabel id={repetitionsId} sx={{ fontSize: fontSize.text, mb: 0.5 }}>
              {check ? 'Erneut prüfen' : 'Umfang der Prüfung'}
            </FormLabel>
            <RadioGroup
              aria-labelledby={repetitionsId}
              value={String(repetitions)}
              onChange={(e) => setRepetitions(Number(e.target.value))}
            >
              {TOOL_CHECK_REPETITIONS.map((option) => (
                <FormControlLabel
                  key={option.value}
                  value={String(option.value)}
                  control={<Radio size="small" />}
                  sx={{ alignItems: 'flex-start', mb: 0.5, '& .MuiRadio-root': { pt: 0.25 } }}
                  label={
                    <Box>
                      <Typography sx={{ fontSize: fontSize.text, fontWeight: 500 }}>
                        {option.label}
                      </Typography>
                      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
                        {option.hint}
                      </Typography>
                    </Box>
                  }
                />
              ))}
            </RadioGroup>
          </FormControl>
        )}
      </DialogContent>
      <DialogActions>
        {check?.status === 'COMPLETED' && (
          <Button onClick={() => void download(check)}>Bericht herunterladen</Button>
        )}
        <Box sx={{ flex: 1 }} />
        <Button onClick={onClose}>Schließen</Button>
        {proposal && (
          <Button variant="outlined" onClick={() => onAdopt(proposal)}>
            Ergebnis übernehmen
          </Button>
        )}
        {canStart && (
          <Button variant="contained" onClick={() => void start()} disabled={starting}>
            {starting ? 'Wird gestartet …' : 'Prüfung starten'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}

function Progress({ check }: { check: LlmModelToolCheckResponse }) {
  const percent = check.totalRuns === 0 ? 0 : (100 * check.completedRuns) / check.totalRuns
  return (
    <Box sx={{ mb: 2 }} role="status">
      <Typography sx={{ fontSize: fontSize.text, mb: 0.75 }}>
        Prüfung läuft: {check.completedRuns} von {check.totalRuns} Fällen bewertet.
      </Typography>
      <LinearProgress
        variant="determinate"
        value={percent}
        aria-label="Fortschritt der Werkzeugprüfung"
      />
    </Box>
  )
}

function Result({ check }: { check: LlmModelToolCheckResponse }) {
  const seconds = check.totalSeconds == null ? null : Math.round(check.totalSeconds)
  return (
    <Box sx={{ mb: 2 }}>
      <Alert severity={check.floorMet ? 'success' : 'warning'} sx={{ mb: 1.5 }}>
        {check.floorMet
          ? 'Mindestanforderung erfüllt: Das Modell darf als werkzeugfähig geführt werden.'
          : 'Mindestanforderung nicht erfüllt: Das Modell darf nicht als werkzeugfähig geführt werden.'}
      </Alert>
      <Table size="small" aria-label="Ergebnis je Prüffrage">
        <TableHead>
          <TableRow>
            <TableCell>Prüffrage</TableCell>
            <TableCell>Ergebnis</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {check.categories.map((category) => (
            <TableRow key={category.category}>
              <TableCell>
                {TOOL_CHECK_CATEGORY_LABELS[category.category] ?? category.category}
                {TOOL_CHECK_FLOOR.includes(category.category) && (
                  <Typography
                    component="span"
                    sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}
                  >
                    {' '}
                    (Pflicht)
                  </Typography>
                )}
              </TableCell>
              <TableCell>{category.summary}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
        Messlauf {check.runId}
        {seconds != null && `, Dauer ${seconds} s`}
        {check.medianModelCallMillis != null &&
          `, Modellaufruf im Median ${(check.medianModelCallMillis / 1000).toFixed(1)} s`}
        .
      </Typography>
    </Box>
  )
}
