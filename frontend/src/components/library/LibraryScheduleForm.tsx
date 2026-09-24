import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import type { ScheduleFrequency, ScheduleWeekday } from '../../types/api'
import {
  scheduleFrequencies,
  scheduleFrequencyLabel,
  scheduleWeekdays,
  scheduleWeekdayLabel,
} from '../../utils/labels'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../../utils/librarySchedule'

interface LibraryScheduleFormProps {
  idPrefix: string
  values: LibraryScheduleValues
  onChange: (patch: Partial<LibraryScheduleValues>) => void
  /** Present only for a CONFLUENCE library - the form then offers the full-sync rhythm. */
  confluence?: ConfluenceFullSyncRhythm
}

/**
 * The one Zeitplan-Formular of a connector library (#485/#1940) - fixed interval steps, stored by
 * the backend as a cron expression (LibraryScheduleCodec); free cron entry is deliberately not part
 * of this surface. Shared by the Bearbeiten-Weg of the Reiter „Quelle" and the Anlage-Assistent.
 */
export default function LibraryScheduleForm({
  idPrefix,
  values,
  onChange,
  confluence,
}: LibraryScheduleFormProps) {
  return (
    <Stack spacing={2}>
      <FormControl size="small" fullWidth>
        <InputLabel id={`${idPrefix}-frequency-label`}>Zeitplan</InputLabel>
        <Select
          labelId={`${idPrefix}-frequency-label`}
          label="Zeitplan"
          value={values.frequency}
          onChange={(e) => onChange({ frequency: e.target.value as ScheduleFrequency })}
        >
          {scheduleFrequencies.map((option) => (
            <MenuItem key={option} value={option}>
              {scheduleFrequencyLabel(option)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      {(values.frequency === 'DAILY' || values.frequency === 'WEEKLY') && (
        <TextField
          label="Uhrzeit"
          type="time"
          value={values.time}
          onChange={(e) => onChange({ time: e.target.value })}
          slotProps={{ inputLabel: { shrink: true } }}
          size="small"
        />
      )}

      {confluence && (
        <TextField
          label="Vollabgleich alle … Tage"
          type="number"
          value={values.fullSyncDays}
          onChange={(e) => onChange({ fullSyncDays: e.target.value })}
          placeholder={confluence.defaultDays != null ? String(confluence.defaultDays) : '7'}
          helperText={`Leer = Vorgabe der Instanz (alle ${confluence.defaultDays ?? 7} Tage). Der Vollabgleich ist verlängerbar, aber nicht abschaltbar — nur er erkennt Löschungen in Confluence.`}
          slotProps={{ htmlInput: { min: 1, max: 365 } }}
          size="small"
        />
      )}

      {values.frequency === 'WEEKLY' && (
        <FormControl size="small" fullWidth>
          <InputLabel id={`${idPrefix}-weekday-label`}>Wochentag</InputLabel>
          <Select
            labelId={`${idPrefix}-weekday-label`}
            label="Wochentag"
            value={values.weekday}
            onChange={(e) => onChange({ weekday: e.target.value as ScheduleWeekday })}
          >
            {scheduleWeekdays.map((option) => (
              <MenuItem key={option} value={option}>
                {scheduleWeekdayLabel(option)}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      )}
    </Stack>
  )
}
