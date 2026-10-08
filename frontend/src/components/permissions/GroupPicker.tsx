import { useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import PublicIcon from '@mui/icons-material/Public'
import GroupsIcon from '@mui/icons-material/Groups'
import type { SelectableGroupResponse } from '../../types/api'
import { useGroupSearch } from '../../hooks/useGroupSearch'
import { groupDetailLine, groupLabel } from './subjectSelection'

interface GroupPickerProps {
  /** Der Screenreader-Name des Eingabefelds — deutsch wie jeder sichtbare Text. */
  ariaLabel: string
  placeholder: string
  value: SelectableGroupResponse | null
  onChange: (value: SelectableGroupResponse | null) => void
  /** Gruppen, die hier nicht mehr in Frage kommen (bereits Mitglied bzw. bereits berechtigt). */
  excludedGroupIds?: string[]
  /** Kennung des Eingabefelds, damit eine eigene Beschriftung (`htmlFor`) ein Ziel hat. */
  inputId?: string
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
  inputId,
}: GroupPickerProps) {
  const { query, setQuery, groups, isLoading, error } = useGroupSearch()
  // What the field shows is not always what is searched for: after a choice it shows the chosen
  // group's label, which must not become a new request.
  const [inputText, setInputText] = useState('')
  const options = groups.filter((group) => !excludedGroupIds.includes(group.id))

  return (
    <Autocomplete
      options={options}
      filterOptions={(option) => option}
      getOptionLabel={(option) => groupLabel(option)}
      getOptionDisabled={(option) => !option.selectable}
      loading={isLoading}
      noOptionsText={
        error ??
        (query.trim().length < 2 ? 'Mindestens zwei Zeichen eingeben' : 'Keine Gruppe gefunden')
      }
      value={value}
      onChange={(_event, next) => onChange(next)}
      inputValue={inputText}
      onInputChange={(_event, next, reason) => {
        // Only typed text is a new request (#778): 'selectOption' and 'reset' set the text to the
        // chosen label, 'clear' empties both.
        setInputText(next)
        if (reason === 'input') setQuery(next)
        else if (reason === 'clear') setQuery('')
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
                <Typography sx={{ fontSize: 13.5 }}>{groupLabel(option)}</Typography>
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
          id={inputId ?? params.id}
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
