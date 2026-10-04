import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { ConnectionLogRetentionResponse } from '../../../types/api'
import {
  getConnectionLogRetention,
  updateConnectionLogRetention,
} from '../../../services/connectionLogApi'
import { apiErrorStatus } from '../../../services/apiErrorDetails'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import BusyButton from '../../a11y/BusyButton'
import PageSection from '../../PageSection'

const MIN_RETENTION_MONTHS = 6
const MAX_RETENTION_MONTHS = 24

function months(value: number): string {
  return value === 1 ? '1 Monat' : `${value} Monate`
}

/**
 * The retention period of the connection log, 6 to 24 months. The system administration sets it
 * here but does not read the log itself; a shortening deletes with the next daily pass.
 */
export default function ConnectionLogRetentionSection() {
  const [current, setCurrent] = useState<ConnectionLogRetentionResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [saveError, setSaveError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(
    () =>
      getConnectionLogRetention()
        .then((loaded) => {
          setCurrent(loaded)
          setDraft(String(loaded.retentionMonths))
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Die Aufbewahrungsfrist konnte nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  const value = Number(draft)
  const valid =
    draft.trim() !== '' &&
    Number.isInteger(value) &&
    value >= MIN_RETENTION_MONTHS &&
    value <= MAX_RETENTION_MONTHS
  const changed = current !== null && valid && value !== current.retentionMonths

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !current || !changed) return
    const shorter = value < current.retentionMonths
    const confirmed = await confirmAction({
      question: `Aufbewahrungsfrist des Verbindungsprotokolls auf ${months(value)} ändern?`,
      consequence: shorter
        ? `Einträge, die älter als ${months(value)} sind, werden mit dem nächsten täglichen Lauf endgültig gelöscht. Die Änderung steht im Nachweisprotokoll.`
        : 'Die längere Frist gilt sofort. Bereits gelöschte Einträge kommen nicht zurück. Die Änderung steht im Nachweisprotokoll.',
      confirmLabel: 'Frist ändern',
      tone: shorter ? 'danger' : 'caution',
    })
    if (!confirmed) return
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateConnectionLogRetention(value)
      setCurrent(saved)
      setDraft(String(saved.retentionMonths))
      notify(`Die Aufbewahrungsfrist beträgt jetzt ${months(saved.retentionMonths)}.`, 'success')
    } catch (err: unknown) {
      setSaveError(
        apiErrorStatus(err) === 400
          ? `Die Frist wurde nicht angenommen: Sie muss zwischen ${MIN_RETENTION_MONTHS} und ${MAX_RETENTION_MONTHS} Monaten liegen.`
          : err instanceof Error
            ? err.message
            : 'Die Aufbewahrungsfrist konnte nicht gespeichert werden.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageSection
      title="Verbindungsprotokoll"
      description="Das Verbindungsprotokoll hält fest, wann Verbindungen zu einem Zugang entstanden, getrennt wurden oder abgelaufen sind. Lesen darf es nur die Revision; hier legen Sie fest, wie lange seine Einträge aufbewahrt werden."
    >
      {loadError ? (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void load()}>
              Erneut laden
            </Button>
          }
        >
          {loadError}
        </Alert>
      ) : current === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Aufbewahrungsfrist wird geladen …
        </Typography>
      ) : (
        <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ alignItems: { sm: 'flex-start' } }}
          >
            <TextField
              label="Aufbewahrungsfrist (Monate)"
              type="number"
              size="small"
              value={draft}
              onChange={(event) => {
                setDraft(event.target.value)
                setSaveError(null)
              }}
              error={!valid}
              helperText={
                valid
                  ? `Erlaubt sind ${MIN_RETENTION_MONTHS} bis ${MAX_RETENTION_MONTHS} Monate. Abschalten lässt sich die Löschung nicht.`
                  : `Bitte eine ganze Zahl von ${MIN_RETENTION_MONTHS} bis ${MAX_RETENTION_MONTHS} eingeben.`
              }
              slotProps={{
                htmlInput: { min: MIN_RETENTION_MONTHS, max: MAX_RETENTION_MONTHS, step: 1 },
              }}
              sx={{ maxWidth: 280 }}
            />
            <BusyButton
              type="submit"
              variant="outlined"
              busy={busy}
              busyAnnouncement="Frist wird gespeichert"
              disabled={!changed && !busy}
              sx={{ mt: { sm: 0.25 } }}
            >
              Speichern
            </BusyButton>
          </Stack>
          {saveError && (
            <Alert severity="error" role="alert" sx={{ mt: 2 }}>
              {saveError}
            </Alert>
          )}
          {current.lastCutoff && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              Gelöscht ist bisher alles vor dem{' '}
              {new Date(current.lastCutoff).toLocaleDateString('de-DE', { dateStyle: 'medium' })}.
            </Typography>
          )}
        </Box>
      )}
    </PageSection>
  )
}
