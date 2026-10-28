import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { SelfDisclosureSettings, SelfDisclosureSettingsUpdate } from '@/shared/api/types'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { getSelfDisclosureSettings, updateSelfDisclosureSettings } from '../api/selfDisclosureApi'

const MIN_DAILY_LIMIT = 1
const MAX_DAILY_LIMIT = 10

/**
 * Emergency switch and daily limit of the self-disclosure, for the system administration. Both act
 * on every instance without a restart; a running export runs to its end. Shows nothing about any
 * person: when someone last exported is not part of any administration view.
 */
export default function SelfDisclosureSettingsSection() {
  const [current, setCurrent] = useState<SelfDisclosureSettings | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [enabled, setEnabled] = useState(true)
  const [draft, setDraft] = useState('')
  const [saveError, setSaveError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const adopt = useCallback((settings: SelfDisclosureSettings) => {
    setCurrent(settings)
    setEnabled(settings.enabled)
    setDraft(String(settings.dailyLimit))
  }, [])

  const load = useCallback(
    () =>
      getSelfDisclosureSettings()
        .then((loaded) => {
          adopt(loaded)
          setLoadError(null)
        })
        .catch(() => {
          setLoadError('Die Einstellungen der Selbstauskunft konnten nicht geladen werden.')
        }),
    [adopt],
  )

  useEffect(() => {
    void load()
  }, [load])

  const limit = Number(draft)
  const valid =
    draft.trim() !== '' &&
    Number.isInteger(limit) &&
    limit >= MIN_DAILY_LIMIT &&
    limit <= MAX_DAILY_LIMIT
  const update: SelfDisclosureSettingsUpdate = {}
  if (current && enabled !== current.enabled) update.enabled = enabled
  if (current && valid && limit !== current.dailyLimit) update.dailyLimit = limit
  const changed = valid && Object.keys(update).length > 0

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !current || !changed) return
    if (update.enabled === false) {
      const confirmed = await confirmAction({
        question: 'Selbstauskunft abschalten?',
        consequence:
          'Niemand kann seine Daten dann selbst herunterladen; die Einstellungen nennen stattdessen den Auskunftsantrag bei der Behörde. Ein laufender Download läuft zu Ende. Die Änderung steht im Nachweisprotokoll.',
        confirmLabel: 'Abschalten',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateSelfDisclosureSettings(update)
      adopt(saved)
      notify(
        saved.enabled
          ? 'Die Einstellungen der Selbstauskunft sind gespeichert.'
          : 'Die Selbstauskunft ist abgeschaltet.',
        'success',
      )
    } catch (err: unknown) {
      setSaveError(
        apiErrorStatus(err) === 400
          ? `Die Tagesgrenze wurde nicht angenommen: Sie muss zwischen ${MIN_DAILY_LIMIT} und ${MAX_DAILY_LIMIT} liegen.`
          : 'Die Einstellungen der Selbstauskunft konnten nicht gespeichert werden.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageSection
      title="Selbstauskunft"
      description="Jede Person lädt ihre gespeicherten Daten unter „Meine Daten“ selbst herunter. Abschalten lässt sich das nur für die ganze Installation; das Recht auf Auskunft bei der Behörde bleibt unberührt."
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
          Einstellungen werden geladen …
        </Typography>
      ) : (
        <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
          <FormControlLabel
            control={
              <Switch
                checked={enabled}
                onChange={(event) => {
                  setEnabled(event.target.checked)
                  setSaveError(null)
                }}
              />
            }
            label="Selbstauskunft anbieten"
          />
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2.5, mt: 0.5 }}>
            {enabled
              ? 'Eingeschaltet: Alle Konten sehen in ihren Einstellungen den Download ihrer Daten.'
              : 'Abgeschaltet: Die Einstellungen verweisen auf den Auskunftsantrag bei der Behörde.'}
          </Typography>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ alignItems: { sm: 'flex-start' } }}
          >
            <TextField
              label="Tagesgrenze (vollständige Downloads je Person und Tag)"
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
                  ? `Erlaubt sind ${MIN_DAILY_LIMIT} bis ${MAX_DAILY_LIMIT}. Ein abgebrochener Download zählt nicht.`
                  : `Bitte eine ganze Zahl von ${MIN_DAILY_LIMIT} bis ${MAX_DAILY_LIMIT} eingeben.`
              }
              slotProps={{
                htmlInput: { min: MIN_DAILY_LIMIT, max: MAX_DAILY_LIMIT, step: 1 },
              }}
              sx={{ maxWidth: 420, flexGrow: 1 }}
            />
            <BusyButton
              type="submit"
              variant="outlined"
              busy={busy}
              busyAnnouncement="Einstellungen werden gespeichert"
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
        </Box>
      )}
    </PageSection>
  )
}
