import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import type { PromptVariable } from '../../types/api'

interface PromptVariableInputProps {
  variable: PromptVariable
  value: string
  onChange: (value: string) => void
  /** The accessible name; the visible label stays with the caller's layout. */
  ariaLabel: string
  /** Offers "no value" in a selection - for a default, not for a required answer. */
  allowEmpty?: boolean
}

/**
 * One value field in the shape its variable type asks for: a line, several lines, a selection of
 * the declared options, or a calendar date (yyyy-MM-dd, as the prompt stores it).
 */
export default function PromptVariableInput({
  variable,
  value,
  onChange,
  ariaLabel,
  allowEmpty = true,
}: PromptVariableInputProps) {
  if (variable.type === 'SELECT') {
    return (
      <Select
        size="small"
        fullWidth
        displayEmpty
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={ariaLabel}
      >
        {allowEmpty && (
          <MenuItem value="">
            <em>keine</em>
          </MenuItem>
        )}
        {(variable.options ?? []).map((option) => (
          <MenuItem key={option} value={option}>
            {option}
          </MenuItem>
        ))}
      </Select>
    )
  }
  return (
    <TextField
      size="small"
      fullWidth
      type={variable.type === 'DATE' ? 'date' : 'text'}
      multiline={variable.type === 'TEXTAREA'}
      minRows={variable.type === 'TEXTAREA' ? 2 : undefined}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      slotProps={{ htmlInput: { 'aria-label': ariaLabel, maxLength: 2000 } }}
    />
  )
}
