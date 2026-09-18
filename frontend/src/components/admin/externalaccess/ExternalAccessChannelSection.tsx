import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  ExternalAccessSettingsResponse,
  ExternalAccessSettingsUpdateRequest,
} from '../../../types/api'
import PageSection from '../../PageSection'
import {
  getExternalAccessSettings,
  updateExternalAccessSettings,
} from '../../../services/externalAccessApi'
import { apiFieldErrors } from '../../../services/apiErrorDetails'

export const SWITCH_CONSEQUENCE =
  'Beschäftigte können Zugangstokens für externe KI-Werkzeuge erzeugen. Das Ausschalten wirkt ' +
  'sofort und bei jedem Aufruf; laufende Verbindungen enden. Die Tokens bleiben erhalten und ' +
  'wirken beim Wiedereinschalten weiter.'

export const CODETERMINATION_HINT =
  'Vor dem Einschalten: Die Beteiligung der Personalvertretung ist zu klären, und die ' +
  'Beschäftigten sind über den Kanal, die dabei erhobenen Angaben und die Freiwilligkeit der ' +
  'Nutzung zu unterrichten. Aus der Nichtnutzung entsteht kein Nachteil.'

interface Draft {
  enabled: boolean
  tokenMaxLifetimeDays: string
  tokenRateLimitPerHour: string
  allowedCidrs: string
  massRetrievalAlertThreshold: string
  serverInstructions: string
}

function draftOf(settings: ExternalAccessSettingsResponse): Draft {
  return {
    enabled: settings.enabled,
    tokenMaxLifetimeDays: String(settings.tokenMaxLifetimeDays),
    tokenRateLimitPerHour: String(settings.tokenRateLimitPerHour),
    allowedCidrs: settings.allowedCidrs.join('\n'),
    massRetrievalAlertThreshold: String(settings.massRetrievalAlertThreshold),
    serverInstructions: settings.serverInstructions,
  }
}

function parseCidrs(value: string): string[] {
  return value
    .split(/[,;\s]+/)
    .map((entry) => entry.trim())
    .filter((entry) => entry !== '')
}

function requestOf(draft: Draft): ExternalAccessSettingsUpdateRequest {
  return {
    enabled: draft.enabled,
    tokenMaxLifetimeDays: Number(draft.tokenMaxLifetimeDays),
    tokenRateLimitPerHour: Number(draft.tokenRateLimitPerHour),
    allowedCidrs: parseCidrs(draft.allowedCidrs),
    massRetrievalAlertThreshold: Number(draft.massRetrievalAlertThreshold),
    serverInstructions: draft.serverInstructions,
  }
}

/**
 * Der gespeicherte Zustand steht bewusst hier und nicht in der Schalterbeschriftung: Solange eine
 * Änderung noch nicht gespeichert ist, meldete ein Name „derzeit aus“ neben `aria-checked="true"`
 * genau am Bedienelement, das den Kanal öffnet, zwei verschiedene Zustände.
 */
function formatStoredState(settings: ExternalAccessSettingsResponse): string {
  const date = new Date(settings.updatedAt)
  const when = Number.isNaN(date.getTime())
    ? settings.updatedAt
    : date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
  const state = settings.enabled ? 'ein' : 'aus'
  return `Gespeichert: ${state} — zuletzt geändert am ${when} durch ${settings.updatedBy ?? 'die Installation selbst'}`
}

/**
 * Die Kanaleinstellungen der Fremdzugänge (#1717) als Reiter der Seite: der installationsweite Schalter — zugleich der
 * Notaus —, die Grenzwerte, die Netzbereiche des Kanals und der Einleitungstext für fremde
 * Werkzeuge.
 *
 * Zustand und Zeitpunkt der letzten Änderung stehen bewusst oben im Abschnitt: Eine
 * Wiederherstellung aus einer Sicherung rollt Schalter, Freigaben und Tokens gemeinsam zurück —
 * samt dem Protokoll, das den Notaus belegen würde. Ein sichtbares Änderungsdatum macht eine
 * solche Rückdatierung erkennbar.
 */
export default function ExternalAccessChannelSection() {
  const [settings, setSettings] = useState<ExternalAccessSettingsResponse | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fieldMessages, setFieldMessages] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)
  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    let active = true
    getExternalAccessSettings()
      .then((loaded) => {
        if (!active) return
        setSettings(loaded)
        setDraft(draftOf(loaded))
      })
      .catch((err: unknown) => {
        if (active) setError(err instanceof Error ? err.message : 'Laden fehlgeschlagen.')
      })
    return () => {
      active = false
    }
  }, [])

  function update(change: Partial<Draft>) {
    setSaved(false)
    setDraft((current) => (current ? { ...current, ...change } : current))
  }

  async function handleSave() {
    if (!draft) return
    setSaved(false)
    setError(null)
    setFieldMessages({})
    setIsSaving(true)
    try {
      const stored = await updateExternalAccessSettings(requestOf(draft))
      setSettings(stored)
      setDraft(draftOf(stored))
      setSaved(true)
    } catch (err: unknown) {
      const perField = apiFieldErrors(err)
      setFieldMessages(Object.fromEntries(perField.map((e) => [e.field, e.message])))
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen.')
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {saved && !error && (
        <Alert severity="success" sx={{ mb: 2 }}>
          Die Einstellungen wurden gespeichert und wirken ab dem nächsten Aufruf.
        </Alert>
      )}

      {!draft || !settings ? (
        <Skeleton variant="rounded" height={320} />
      ) : (
        <Stack spacing={4}>
          <PageSection title="Schalter der Installation">
            <Stack spacing={1.5}>
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.enabled}
                    onChange={(e) => update({ enabled: e.target.checked })}
                  />
                }
                label="Fremdzugänge erlauben"
              />
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {formatStoredState(settings)}
              </Typography>
              <Typography sx={{ fontSize: 13 }}>{SWITCH_CONSEQUENCE}</Typography>
              <Alert severity="info">{CODETERMINATION_HINT}</Alert>
            </Stack>
          </PageSection>

          <PageSection
            title="Grenzwerte des Kanals"
            description="Sie gelten für den ganzen Kanal dieser Installation — die Netzbereiche und die Alarmschwelle kanalweit, die beiden übrigen Werte für jedes Zugangstoken."
          >
            <Stack spacing={3}>
              <TextField
                label="Ablauf-Obergrenze für Zugangstokens (Tage)"
                type="number"
                value={draft.tokenMaxLifetimeDays}
                onChange={(e) => update({ tokenMaxLifetimeDays: e.target.value })}
                error={Boolean(fieldMessages.tokenMaxLifetimeDays)}
                helperText={
                  fieldMessages.tokenMaxLifetimeDays ??
                  'Zwischen 1 und 365 Tagen. Kein Token kann länger gültig sein.'
                }
                fullWidth
              />
              <TextField
                label="Anfragen je Token und Stunde"
                type="number"
                value={draft.tokenRateLimitPerHour}
                onChange={(e) => update({ tokenRateLimitPerHour: e.target.value })}
                error={Boolean(fieldMessages.tokenRateLimitPerHour)}
                helperText={
                  fieldMessages.tokenRateLimitPerHour ??
                  'Eine Lastbremse, kein Schutz vor Massenabfluss: Was in einer Nacht nicht geht, geht in neunzig Tagen.'
                }
                fullWidth
              />
              <TextField
                label="Abflussalarm ab (Abrufe je Stunde)"
                type="number"
                value={draft.massRetrievalAlertThreshold}
                onChange={(e) => update({ massRetrievalAlertThreshold: e.target.value })}
                error={Boolean(fieldMessages.massRetrievalAlertThreshold)}
                helperText={
                  fieldMessages.massRetrievalAlertThreshold ??
                  'Über den ganzen Kanal gezählt. Die Überschreitung meldet sich der Systemverwaltung; ein Nachweisprotokoll entsteht daraus nicht.'
                }
                fullWidth
              />
              <TextField
                label="Netzbereiche des Kanals"
                value={draft.allowedCidrs}
                onChange={(e) => update({ allowedCidrs: e.target.value })}
                error={Boolean(fieldMessages.allowedCidrs)}
                helperText={
                  fieldMessages.allowedCidrs ??
                  'Eine Netzangabe je Zeile (z. B. 10.0.0.0/8). Voreinstellung ist das Hausnetz. Eine leere Liste schließt den Kanal für jede Adresse. Geprüft wird die über die Trusted-Proxy-Auflösung ermittelte Adresse (OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS).'
                }
                multiline
                minRows={3}
                fullWidth
              />
            </Stack>
          </PageSection>

          <PageSection
            title="Einleitungstext für fremde Werkzeuge"
            description="Er wird dem fremden Modell beim Verbindungsaufbau mitgegeben, damit es erkennt, welche Fragen hierher gehören. Seine Änderung ändert keine Reichweite und wird nicht protokolliert."
            action={
              <Button
                onClick={() => update({ serverInstructions: settings.defaultServerInstructions })}
                disabled={draft.serverInstructions === settings.defaultServerInstructions}
              >
                Vorgabetext
              </Button>
            }
          >
            <TextField
              label="Einleitungstext"
              value={draft.serverInstructions}
              onChange={(e) => update({ serverInstructions: e.target.value })}
              error={Boolean(fieldMessages.serverInstructions)}
              helperText={fieldMessages.serverInstructions ?? 'Höchstens 4000 Zeichen.'}
              multiline
              minRows={4}
              fullWidth
            />
          </PageSection>

          <Box>
            <Button variant="contained" onClick={handleSave} disabled={isSaving}>
              Speichern
            </Button>
          </Box>
        </Stack>
      )}
    </>
  )
}
