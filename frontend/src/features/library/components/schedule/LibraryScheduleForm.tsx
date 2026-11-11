import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import type { ScheduleFrequency, ScheduleWeekday } from '@/shared/api/types'
import {
  scheduleFrequencies,
  scheduleFrequencyLabel,
  scheduleWeekdays,
  scheduleWeekdayLabel,
} from './scheduleLabels'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../../utils/librarySchedule'

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
        <FormField
          select
          label="Häufigkeit"
          size="small"
          fullWidth
          value={values.frequency}
          onChange={(e) => onChange({ frequency: e.target.value as ScheduleFrequency })}
        >
          {scheduleFrequencies.map((option) => (
            <MenuItem key={option} value={option}>
              {scheduleFrequencyLabel(option)}
            </MenuItem>
          ))}
        </FormField>
      </Box>

      {timed && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {weekly && (
            <Box sx={{ flex: 1 }}>
              <FormField
                select
                label="Wochentag"
                size="small"
                fullWidth
                value={values.weekday}
                onChange={(e) => onChange({ weekday: e.target.value as ScheduleWeekday })}
              >
                {scheduleWeekdays.map((option) => (
                  <MenuItem key={option} value={option}>
                    {scheduleWeekdayLabel(option)}
                  </MenuItem>
                ))}
              </FormField>
            </Box>
          )}
          <Box sx={{ flex: 1 }}>
            <FormField
              label="Uhrzeit"
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
          <FormField
            label="Vollabgleich alle … Tage"
            id={`${idPrefix}-full-sync-days`}
            type="number"
            fullWidth
            value={values.fullSyncDays}
            onChange={(e) => onChange({ fullSyncDays: e.target.value })}
            placeholder={
              confluence.defaultDays != null ? String(confluence.defaultDays) : undefined
            }
            helperText={`${
              confluence.defaultDays != null
                ? `Leer = Vorgabe der Instanz (${confluence.defaultDays === 1 ? 'täglich' : `alle ${confluence.defaultDays} Tage`}).`
                : 'Leer = Vorgabe der Instanz.'
            }`}
            slotProps={{ htmlInput: { min: 1, max: 365 } }}
            size="small"
          />
        </Box>
      )}
    </Stack>
  )
}
