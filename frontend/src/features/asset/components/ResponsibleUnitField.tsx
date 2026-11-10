import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import type { GroupListResponse } from '@/shared/api/types'
import type { ResponsibleDraft } from './responsibility'

const HELP =
  'Eine Gruppe wählen oder eine Organisationseinheit bzw. Funktion eintragen, etwa „Referat Z3 – Organisation“. Keine Einzelperson.'

/**
 * The responsible unit of an agent or a template: one field that offers the person's groups and
 * takes any other unit or function as text. The label stands above the field; a missing value
 * shows its message at the field, the form stays sendable.
 */
export default function ResponsibleUnitField({
  id,
  groups,
  value,
  onChange,
  error,
}: {
  id: string
  groups: GroupListResponse[]
  value: ResponsibleDraft
  onChange: (draft: ResponsibleDraft) => void
  error?: string
}) {
  const options = groups.filter((group) => !group.deactivated)
  return (
    <Box>
      <FieldLabel htmlFor={id}>Verantwortliche Stelle</FieldLabel>
      <Autocomplete
        id={id}
        freeSolo
        options={options}
        size="small"
        getOptionLabel={(option) => (typeof option === 'string' ? option : option.name)}
        value={value.group ? (options.find((g) => g.id === value.group?.id) ?? null) : null}
        inputValue={value.text}
        onChange={(_e, chosen) => {
          if (chosen && typeof chosen !== 'string') {
            onChange({ group: { id: chosen.id, name: chosen.name }, text: chosen.name })
          }
        }}
        onInputChange={(_e, text, reason) => {
          if (reason === 'reset' && value.group && text === value.group.name) return
          onChange({
            group: value.group && text === value.group.name ? value.group : null,
            text,
          })
        }}
        noOptionsText="Keine Gruppe – Eingabe gilt als Organisationseinheit oder Funktion"
        renderInput={(params) => (
          <TextField
            {...params}
            error={Boolean(error)}
            helperText={error ?? HELP}
            slotProps={{
              ...params.slotProps,
              htmlInput: { ...params.slotProps.htmlInput, maxLength: 200 },
            }}
          />
        )}
      />
    </Box>
  )
}
