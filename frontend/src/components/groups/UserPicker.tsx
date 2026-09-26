import { useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import TextField from '@mui/material/TextField'
import type { UserSummary } from '../../types/api'
import { useUserSearch } from '../../hooks/useUserSearch'

interface UserPickerProps {
  /** Der Screenreader-Name des Eingabefelds — deutsch wie jeder sichtbare Text. */
  ariaLabel: string
  placeholder: string
  value: UserSummary | null
  onChange: (value: UserSummary | null) => void
  /** Konten, die hier nicht mehr in Frage kommen (bereits Mitglied bzw. bereits verantwortlich). */
  excludedUserIds: string[]
  /** Kennung des Eingabefelds, damit eine eigene Beschriftung (`htmlFor`) ein Ziel hat. */
  inputId?: string
  /** Füllt die Zeile, sodass eine Schaltfläche daneben bündig mit den Feldern darüber abschließt. */
  grow?: boolean
}

function optionLabel(user: UserSummary): string {
  if (user.displayName) return `${user.displayName} (${user.email ?? user.id})`
  return user.email ?? user.id
}

/**
 * Die serverseitige Kontensuche als Auswahlfeld (#777) — nutzbar für jedes angemeldete Konto,
 * anders als die Kontenliste der Administration. Die Gruppenverwaltung der Verantwortlichen
 * (#1814) braucht genau das: Sie steht Konten ohne Systemrolle offen.
 */
export default function UserPicker({
  ariaLabel,
  placeholder,
  value,
  onChange,
  excludedUserIds,
  inputId,
  grow = false,
}: UserPickerProps) {
  const { query, setQuery, users, isLoading, error } = useUserSearch()
  // What the field shows is not always what is searched for: after a choice it shows the chosen
  // person's label, which must not become a new request.
  const [inputText, setInputText] = useState('')
  const options = users.filter((user) => !excludedUserIds.includes(user.id))

  return (
    <Autocomplete
      options={options}
      filterOptions={(option) => option}
      getOptionLabel={optionLabel}
      loading={isLoading}
      noOptionsText={
        error ?? (query.trim().length < 2 ? 'Mindestens zwei Zeichen eingeben' : 'Keine Treffer')
      }
      value={value}
      onChange={(_event, next) => onChange(next)}
      inputValue={inputText}
      onInputChange={(_event, next, reason) => {
        // Das Feld zeigt immer, was MUI vorgibt - nach einer Auswahl das Label der Person. Eine
        // neue Anfrage ist aber nur, was getippt wurde (#778): 'selectOption' und 'reset' als
        // Eingabe weiterzugeben suchte nach nie getipptem Text. 'clear' leert beides.
        setInputText(next)
        if (reason === 'input') setQuery(next)
        else if (reason === 'clear') setQuery('')
      }}
      isOptionEqualToValue={(option, selected) => option.id === selected.id}
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
      sx={{ minWidth: 280, ...(grow ? { flexGrow: 1 } : {}) }}
    />
  )
}
