import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { ConnectionLogRetentionResponse } from '@/shared/api/types'
import { getConnectionLogRetention, updateConnectionLogRetention } from '../api/connectionLogApi'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { fontSize, formWidth } from '@/shared/theme/tokens'
import { formatDate } from '@/shared/utils/formatDate'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

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
      confirmLabel: 'Ändern',
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
        <LoadError
          message="Die Aufbewahrungsfrist konnte nicht geladen werden."
          detail={loadError}
          onRetry={() => void load()}
        />
      ) : current === null ? (
        <LoadingState shape="lines" count={2} />
      ) : (
        <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
          <FormField
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
            sx={{ maxWidth: formWidth.column }}
            action={
              <BusyButton
                type="submit"
                variant="outlined"
                busy={busy}
                busyAnnouncement="Frist wird gespeichert"
                disabled={!changed && !busy}
              >
                Speichern
              </BusyButton>
            }
          />
          {saveError && (
            <Alert severity="error" role="alert" sx={{ mt: 2 }}>
              {saveError}
            </Alert>
          )}
          {current.lastCutoff && (
            <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
              Gelöscht ist bisher alles vor dem {formatDate(current.lastCutoff)}.
            </Typography>
          )}
        </Box>
      )}
    </PageSection>
  )
}
