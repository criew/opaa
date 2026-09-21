import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import PublicIcon from '@mui/icons-material/Public'
import GroupsIcon from '@mui/icons-material/Groups'
import type { SelectableGroupResponse } from '../../types/api'
import { useGroupSearch } from '../../hooks/useGroupSearch'
import { groupDetailLine } from './subjectSelection'

interface GroupPickerProps {
  /** Der Screenreader-Name des Eingabefelds — deutsch wie jeder sichtbare Text. */
  ariaLabel: string
  placeholder: string
  value: SelectableGroupResponse | null
  onChange: (value: SelectableGroupResponse | null) => void
  /** Gruppen, die hier nicht mehr in Frage kommen (bereits Mitglied bzw. bereits berechtigt). */
  excludedGroupIds?: string[]
}

/**
 * Die Gruppensuche der Subjekt-Auswahl (#1820, ADR-0036 Entscheidung 9). Jede Zeile trägt die
 * Herkunft, damit zwei gleichnamige Gruppen unterscheidbar sind; eine Gruppe eines externen
 * Anbieters ist mit einem eigenen Symbol abgehoben, nicht nur mit Text. Eine nicht wirksame Gruppe
 * bleibt sichtbar, aber nicht wählbar — mit dem Grund.
 */
export default function GroupPicker({
  ariaLabel,
  placeholder,
  value,
  onChange,
  excludedGroupIds = [],
}: GroupPickerProps) {
  const { query, setQuery, groups, isLoading, error } = useGroupSearch()
  const options = groups.filter((group) => !excludedGroupIds.includes(group.id))

  return (
    <Autocomplete
      options={options}
      filterOptions={(option) => option}
      getOptionLabel={(option) => option.name}
      getOptionDisabled={(option) => !option.selectable}
      loading={isLoading}
      noOptionsText={
        error ??
        (query.trim().length < 2 ? 'Mindestens zwei Zeichen eingeben' : 'Keine Gruppe gefunden')
      }
      value={value}
      onChange={(_event, next) => onChange(next)}
      inputValue={query}
      onInputChange={(_event, next, reason) => {
        // 'reset' feuert, wenn das Eingabefeld auf die gerade gewählte Option gesetzt wird - das
        // als neue Eingabe weiterzugeben löste eine Suche nach nie getipptem Text aus.
        if (reason !== 'reset') setQuery(next)
      }}
      isOptionEqualToValue={(option, selected) => option.id === selected.id}
      renderOption={(props, option) => {
        const { key, ...optionProps } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...optionProps}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', width: '100%' }}>
              {option.provider?.external ? (
                <PublicIcon
                  fontSize="small"
                  color="warning"
                  titleAccess="Gruppe eines externen Anbieters"
                  sx={{ mt: 0.25 }}
                />
              ) : (
                <GroupsIcon fontSize="small" color="disabled" sx={{ mt: 0.25 }} />
              )}
              <Stack spacing={0}>
                <Typography sx={{ fontSize: 13.5 }}>{option.name}</Typography>
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  {groupDetailLine(option)}
                </Typography>
              </Stack>
            </Stack>
          </Box>
        )
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          placeholder={placeholder}
          slotProps={{
            ...params.slotProps,
            htmlInput: { ...params.slotProps.htmlInput, 'aria-label': ariaLabel },
          }}
        />
      )}
      size="small"
      sx={{ minWidth: 280, flex: 1 }}
    />
  )
}
