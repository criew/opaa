import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '../wizard/FieldLabel'

const RELEASE_HELP = 'Erst freigegeben ist die Gruppe für andere Rechtevergebende wählbar.'

const PROTECTION_HELP =
  'Für Gruppen der Personalvertretung, der Schwerbehindertenvertretung, der Gleichstellung und ' +
  'für Personalvorgänge. Eine geschützte Gruppe ist nicht über die Suche auffindbar, erscheint in ' +
  'fremden Listen ohne Namen, und wer ihr ein Recht gibt, sieht weder ihre Mitglieder noch ihre ' +
  'Größe. Über den Schutz entscheidet die Systemverwaltung.'

/** The editable values of an internal group, as a draft that „Anlegen“ or „Speichern“ commits. */
export interface GroupFormValues {
  name: string
  description: string
  released: boolean
  protectedGroup: boolean
}

interface GroupFormFieldsProps {
  values: GroupFormValues
  onChange: (values: GroupFormValues) => void
  /** Distinguishes the field ids of the create and the edit dialog. */
  idPrefix: string
  /** Only the system administration decides the protection; everybody else sees no switch. */
  canProtect: boolean
  /** Name and description exist only for an internal group; a provider group has the switch only. */
  isInternal?: boolean
  autoFocusName?: boolean
}

/**
 * Die Felder einer Gruppe, gemeinsam für „Gruppe anlegen“ und „Bearbeiten“ (#1978): Name,
 * Beschreibung, Freigabe und - nur für die Systemverwaltung - der Schutz. Beide Dialoge sehen damit
 * gleich aus und sprechen dieselbe Sprache.
 */
export default function GroupFormFields({
  values,
  onChange,
  idPrefix,
  canProtect,
  isInternal = true,
  autoFocusName = false,
}: GroupFormFieldsProps) {
  const set = (patch: Partial<GroupFormValues>) => onChange({ ...values, ...patch })

  return (
    <Stack spacing={2}>
      {isInternal && (
        <>
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-name`}>Name der Gruppe</FieldLabel>
            <TextField
              // Focus moves to the first field of a dialog the user just opened (WAI-ARIA APG
              // dialog pattern).
              // eslint-disable-next-line jsx-a11y-x/no-autofocus
              autoFocus={autoFocusName}
              id={`${idPrefix}-name`}
              fullWidth
              size="small"
              value={values.name}
              onChange={(e) => set({ name: e.target.value })}
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-description`}>Beschreibung (optional)</FieldLabel>
            <TextField
              id={`${idPrefix}-description`}
              fullWidth
              size="small"
              multiline
              minRows={2}
              value={values.description}
              onChange={(e) => set({ description: e.target.value })}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
          </Box>
          <Box>
            <FormControlLabel
              control={
                <Switch
                  checked={values.released}
                  onChange={(e) => set({ released: e.target.checked })}
                />
              }
              label="Zur Verwendung freigegeben"
            />
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{RELEASE_HELP}</Typography>
          </Box>
        </>
      )}
      {canProtect && (
        <Box>
          <FormControlLabel
            control={
              <Switch
                checked={values.protectedGroup}
                onChange={(e) => set({ protectedGroup: e.target.checked })}
              />
            }
            label="Geschützte Gruppe"
          />
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            {PROTECTION_HELP}
          </Typography>
        </Box>
      )}
    </Stack>
  )
}
