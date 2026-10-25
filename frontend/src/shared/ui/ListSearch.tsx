import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import SearchIcon from '@mui/icons-material/Search'

/**
 * The one search field of a list on a detail page: magnifier, placeholder as example, the name
 * for assistive technology - the same in documents, permissions, spaces and value lists.
 */
export default function ListSearch({
  value,
  onChange,
  placeholder,
  label,
  fullWidth = true,
}: {
  value: string
  onChange: (value: string) => void
  placeholder: string
  /** The accessible name, e.g. „Freigaben durchsuchen“. */
  label: string
  fullWidth?: boolean
}) {
  return (
    <TextField
      size="small"
      fullWidth={fullWidth}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      placeholder={placeholder}
      slotProps={{
        input: {
          startAdornment: (
            <InputAdornment position="start">
              <SearchIcon sx={{ fontSize: 16 }} />
            </InputAdornment>
          ),
        },
        htmlInput: { 'aria-label': label },
      }}
    />
  )
}
