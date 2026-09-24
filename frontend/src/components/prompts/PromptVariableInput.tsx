import { useId } from 'react'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import type { PromptVariable } from '../../types/api'

interface PromptVariableInputProps {
  variable: PromptVariable
  value: string
  onChange: (value: string) => void
  /**
   * The accessible name when the field carries no visible `label` - the visible label then stays
   * with the caller's layout.
   */
  ariaLabel?: string
  /** A visible label on the field itself; it is then also the accessible name. */
  label?: string
  /** Marks the field as required (asterisk, `required` attribute). */
  required?: boolean
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
  label,
  required = false,
  allowEmpty = true,
}: PromptVariableInputProps) {
  const labelId = useId()
  if (variable.type === 'SELECT') {
    const select = (
      <Select
        size="small"
        fullWidth
        displayEmpty={label === undefined}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        {...(label === undefined
          ? { 'aria-label': ariaLabel }
          : { labelId, label, SelectDisplayProps: { 'aria-labelledby': labelId } })}
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
    if (label === undefined) return select
    return (
      <FormControl size="small" fullWidth required={required}>
        <InputLabel id={labelId}>{label}</InputLabel>
        {select}
      </FormControl>
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
      label={label}
      required={required}
      slotProps={{
        htmlInput: { ...(label === undefined ? { 'aria-label': ariaLabel } : {}), maxLength: 2000 },
        ...(label !== undefined && variable.type === 'DATE'
          ? { inputLabel: { shrink: true } }
          : {}),
      }}
    />
  )
}
