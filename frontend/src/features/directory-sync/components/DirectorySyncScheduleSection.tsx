import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { DirectorySyncStatusResponse, OidcProviderResponse } from '@/shared/api/types'
import { clockLabel, intervalLabel, nextRunOf, switchOnBlocker } from './directorySyncState'
import { fontSize, formWidth } from '@/shared/theme/tokens'
import SettingValueRow from '@/shared/ui/settings/SettingValueRow'

/** The bounds the backend enforces (OidcProviderService), and its default of six hours. */
const MIN_INTERVAL_MINUTES = 5
const MAX_INTERVAL_MINUTES = 10080
const DEFAULT_INTERVAL_MINUTES = 360

function parseInterval(value: string): number | null {
  if (!/^\d+$/.test(value.trim())) return null
  const minutes = Number(value)
  return minutes >= MIN_INTERVAL_MINUTES && minutes <= MAX_INTERVAL_MINUTES ? minutes : null
}

interface ScheduleSectionProps {
  provider: OidcProviderResponse
  status: DirectorySyncStatusResponse | undefined
  busy: boolean
  /** Sends the switch and the interval; null leaves the interval to the server. */
  onSave: (enabled: boolean, intervalMinutes: number | null, savedInterval: boolean) => void
}

/**
 * Whether the sync runs, and how often - the two settings of the provider row. The switch acts at
 * once and sends the saved interval; the interval is a value row of its own.
 */
export default function DirectorySyncScheduleSection({
  provider,
  status,
  busy,
  onSave,
}: ScheduleSectionProps) {
  const saved = provider.directorySyncIntervalMinutes ?? DEFAULT_INTERVAL_MINUTES
  const [interval, setIntervalValue] = useState(String(saved))
  const minutes = parseInterval(interval)
  const enabled = provider.directorySyncEnabled
  const blocker = switchOnBlocker(provider)
  const next = nextRunOf(provider, status)

  const switchHint = enabled
    ? provider.enabled
      ? `OPAA liest Gruppen, Mitgliedschaften und Kontostatus ${intervalLabel(saved)} aus dem Verzeichnis dieses Anbieters.`
      : 'Eingeschaltet, aber pausiert: Der Anbieter ist deaktiviert.'
    : 'Die Gruppen dieses Anbieters werden nicht aus seinem Verzeichnis abgeglichen.'

  return (
    <Stack spacing={2}>
      <Box>
        <FormControlLabel
          control={
            <Switch
              checked={enabled}
              disabled={busy || (!enabled && Boolean(blocker))}
              onChange={(event) => onSave(event.target.checked, saved, false)}
            />
          }
          label="Abgleich eingeschaltet"
        />
        <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', ml: 0.5 }}>
          {switchHint}
        </Typography>
        {blocker && (
          <Alert severity="info" sx={{ mt: 1 }}>
            {blocker}
          </Alert>
        )}
      </Box>

      <SettingValueRow
        title="Intervall"
        headingLevel="h4"
        value={intervalLabel(saved)}
        canEdit={enabled}
        onOpen={() => setIntervalValue(String(saved))}
        onSave={async () => {
          if (busy || minutes === null) return false
          if (minutes !== saved) onSave(true, minutes, true)
          return true
        }}
        editor={
          <FormField
            size="small"
            label="Intervall in Minuten"
            value={interval}
            onChange={(event) => setIntervalValue(event.target.value)}
            error={minutes === null}
            helperText={
              minutes === null
                ? `Eine ganze Zahl von ${MIN_INTERVAL_MINUTES} bis ${MAX_INTERVAL_MINUTES} (eine Woche).`
                : `${intervalLabel(minutes)} · ${MIN_INTERVAL_MINUTES} Minuten bis eine Woche`
            }
            slotProps={{ htmlInput: { inputMode: 'numeric' } }}
            numeric
            sx={{ maxWidth: formWidth.column }}
          />
        }
      >
        {!enabled && (
          <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
            Das Intervall lässt sich nach dem Einschalten ändern.
          </Typography>
        )}
      </SettingValueRow>

      {next.kind !== 'NONE' && (
        <Typography sx={{ fontSize: fontSize.text }}>
          Nächster Lauf:{' '}
          <strong>{next.kind === 'DUE' ? 'jetzt fällig' : clockLabel(next.at)}</strong>
        </Typography>
      )}
    </Stack>
  )
}
