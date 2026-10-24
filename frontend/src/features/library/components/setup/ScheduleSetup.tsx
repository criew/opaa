import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../../utils/librarySchedule'
import LibraryScheduleForm from '../LibraryScheduleForm'

interface ScheduleSetupProps {
  idPrefix: string
  values: LibraryScheduleValues
  onChange: (patch: Partial<LibraryScheduleValues>) => void
  rhythm?: ConfluenceFullSyncRhythm
  /** Create mode: the switch for the first run right after creation. */
  firstRun?: { checked: boolean; onChange: (checked: boolean) => void; hint: string }
}

/** The fourth section of the setup frame, the same in the wizard and in the edit dialog. */
export default function ScheduleSetup({ idPrefix, values, onChange, rhythm, firstRun }: ScheduleSetupProps) {
  return (
    <Box id={`${idPrefix}-schedule`} tabIndex={-1} sx={{ outline: 'none', maxWidth: 360 }}>
      <LibraryScheduleForm idPrefix={idPrefix} values={values} onChange={onChange} confluence={rhythm} />
      {firstRun && (
        <Box sx={{ mt: 2 }}>
          <FormControlLabel
            control={
              <Switch checked={firstRun.checked} onChange={(event) => firstRun.onChange(event.target.checked)} />
            }
            label="Erste Indizierung sofort starten"
          />
          <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
            {firstRun.checked ? firstRun.hint : 'Sonst beginnt sie mit dem Zeitplan oder über „Jetzt indizieren“.'}
          </Typography>
        </Box>
      )}
    </Box>
  )
}
