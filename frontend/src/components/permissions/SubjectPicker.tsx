import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { PermissionSubjectType } from '../../types/api'
import type { SubjectSelection } from './subjectSelection'
import UserPicker from '../groups/UserPicker'
import GroupPicker from './GroupPicker'

interface SubjectPickerProps {
  value: SubjectSelection
  onChange: (value: SubjectSelection) => void
  /** Kennung der Beschriftung „Person oder Gruppe"; je Dialog eindeutig. */
  labelId: string
  /** Personen, die hier nicht mehr in Frage kommen. */
  excludedUserIds?: string[]
  /** Gruppen, die hier nicht mehr in Frage kommen. */
  excludedGroupIds?: string[]
  /** Blendet das Suchfeld aus, wenn der Aufrufer stattdessen eine Kennung eingeben lässt. */
  hideSearch?: boolean
}

/**
 * Die gemeinsame Subjekt-Auswahl von Freigabedialog und Space-Mitgliederverwaltung (#1820):
 * Person oder Gruppe, beide über eine serverseitige Suche. Welche Gruppen erscheinen, entscheidet
 * der Dienst; diese Auswahl ist die Bequemlichkeit, nie die Durchsetzung.
 */
export default function SubjectPicker({
  value,
  onChange,
  labelId,
  excludedUserIds = [],
  excludedGroupIds = [],
  hideSearch = false,
}: SubjectPickerProps) {
  return (
    <Stack spacing={1}>
      <FormControl>
        <Typography id={labelId} component="span" sx={{ fontSize: 13, fontWeight: 600 }}>
          Empfänger
        </Typography>
        <RadioGroup
          row
          aria-labelledby={labelId}
          value={value.type}
          onChange={(event) =>
            onChange({
              type: event.target.value as PermissionSubjectType,
              user: null,
              group: null,
            })
          }
        >
          <FormControlLabel value="USER" control={<Radio />} label="Person" />
          <FormControlLabel value="GROUP" control={<Radio />} label="Gruppe" />
        </RadioGroup>
      </FormControl>
      {hideSearch ? null : value.type === 'USER' ? (
        <UserPicker
          ariaLabel="Person suchen"
          placeholder="Person suchen …"
          value={value.user}
          onChange={(user) => onChange({ ...value, user })}
          excludedUserIds={excludedUserIds}
        />
      ) : (
        <GroupPicker
          ariaLabel="Gruppe suchen"
          placeholder="Gruppe suchen …"
          value={value.group}
          onChange={(group) => onChange({ ...value, group })}
          excludedGroupIds={excludedGroupIds}
        />
      )}
      {value.type === 'GROUP' && !hideSearch && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          Eine geschützte Gruppe erscheint nur, wenn Sie ihre vollständige Bezeichnung eingeben.
        </Typography>
      )}
    </Stack>
  )
}
