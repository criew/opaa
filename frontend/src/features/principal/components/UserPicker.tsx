import { useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import TextField from '@mui/material/TextField'
import type { UserSummary } from '@/shared/api/types'
import { useUserSearch } from '../hooks/useUserSearch'

interface UserPickerProps {
  /** The input field's screen reader name — German like every visible text. */
  ariaLabel: string
  placeholder: string
  value: UserSummary | null
  onChange: (value: UserSummary | null) => void
  /** Accounts no longer eligible here (already a member or already a steward). */
  excludedUserIds: string[]
  /** Identifier of the input field, so that an own label (`htmlFor`) has a target. */
  inputId?: string
  /** Fills the row, so that a button next to it ends flush with the fields above. */
  grow?: boolean
}

function optionLabel(user: UserSummary): string {
  if (user.displayName) return `${user.displayName} (${user.email ?? user.id})`
  return user.email ?? user.id
}

/**
 * The server-side account search as a select field — usable for every signed-in account,
 * unlike the administration's account list. The stewards' group management
 * needs exactly that: it is open to accounts without a system role.
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
        // The field always shows what MUI dictates - after a selection the person's label. A
        // new request, however, is only what was typed: passing on 'selectOption' and 'reset' as
        // input searched for text that was never typed. 'clear' empties both.
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
