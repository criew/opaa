import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import type { ScheduleFrequency, ScheduleWeekday } from '@/shared/api/types'
import {
  scheduleFrequencies,
  scheduleFrequencyLabel,
  scheduleWeekdays,
  scheduleWeekdayLabel,
} from './scheduleLabels'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../utils/librarySchedule'

interface LibraryScheduleFormProps {
  idPrefix: string
  values: LibraryScheduleValues
  onChange: (patch: Partial<LibraryScheduleValues>) => void
  /** Present only for a connector with its own full-sync rhythm - the form then offers it. */
  confluence?: ConfluenceFullSyncRhythm
}

/**
 * The one Zeitplan-Formular of a connector library - fixed interval steps, stored by
 * the backend as a cron expression (LibraryScheduleCodec); free cron entry is deliberately not part
 * of this surface. Shared by the Bearbeiten-Weg of the Reiter „Quelle" and the Anlage-Assistent.
 */
export default function LibraryScheduleForm({
  idPrefix,
  values,
  onChange,
  confluence,
}: LibraryScheduleFormProps) {
  const weekly = values.frequency === 'WEEKLY'
  const timed = values.frequency === 'DAILY' || weekly
  return (
    <Stack spacing={2}>
      <Box>
        <FieldLabel id={`${idPrefix}-frequency-label`}>Häufigkeit</FieldLabel>
        <Select
          size="small"
          fullWidth
          labelId={`${idPrefix}-frequency-label`}
          value={values.frequency}
          onChange={(e) => onChange({ frequency: e.target.value as ScheduleFrequency })}
        >
          {scheduleFrequencies.map((option) => (
            <MenuItem key={option} value={option}>
              {scheduleFrequencyLabel(option)}
            </MenuItem>
          ))}
        </Select>
      </Box>

      {timed && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {weekly && (
            <Box sx={{ flex: 1 }}>
              <FieldLabel id={`${idPrefix}-weekday-label`}>Wochentag</FieldLabel>
              <Select
                size="small"
                fullWidth
                labelId={`${idPrefix}-weekday-label`}
                value={values.weekday}
                onChange={(e) => onChange({ weekday: e.target.value as ScheduleWeekday })}
              >
                {scheduleWeekdays.map((option) => (
                  <MenuItem key={option} value={option}>
                    {scheduleWeekdayLabel(option)}
                  </MenuItem>
                ))}
              </Select>
            </Box>
          )}
          <Box sx={{ flex: 1 }}>
            <FieldLabel htmlFor={`${idPrefix}-time`}>Uhrzeit</FieldLabel>
            <TextField
              id={`${idPrefix}-time`}
              type="time"
              fullWidth
              value={values.time}
              onChange={(e) => onChange({ time: e.target.value })}
              size="small"
            />
          </Box>
        </Stack>
      )}

      {confluence && (
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-full-sync-days`}>Vollabgleich alle … Tage</FieldLabel>
          <TextField
            id={`${idPrefix}-full-sync-days`}
            type="number"
            fullWidth
            value={values.fullSyncDays}
            onChange={(e) => onChange({ fullSyncDays: e.target.value })}
            placeholder={
              confluence.defaultDays != null ? String(confluence.defaultDays) : undefined
            }
            helperText={
              confluence.defaultDays != null
                ? `Leer = Vorgabe der Instanz (${confluence.defaultDays === 1 ? 'täglich' : `alle ${confluence.defaultDays} Tage`}).`
                : 'Leer = Vorgabe der Instanz.'
            }
            slotProps={{ htmlInput: { min: 1, max: 365 } }}
            size="small"
          />
        </Box>
      )}
    </Stack>
  )
}
