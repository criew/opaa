import MenuItem from '@mui/material/MenuItem'
import FormField from '@/shared/ui/form/FormField'
import type { PromptVariable } from '@/shared/api/types'

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
  /** Marks the field as required (`required` attribute, no asterisk). */
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
  const field = {
    size: 'small',
    fullWidth: true,
    label: label ?? ariaLabel,
    hideLabel: label === undefined,
    value,
    required,
  } as const
  if (variable.type === 'SELECT') {
    return (
      <FormField
        {...field}
        select
        onChange={(e) => onChange(e.target.value)}
        slotProps={{ select: { displayEmpty: label === undefined } }}
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
      </FormField>
    )
  }
  return (
    <FormField
      {...field}
      type={variable.type === 'DATE' ? 'date' : 'text'}
      multiline={variable.type === 'TEXTAREA'}
      onChange={(e) => onChange(e.target.value)}
      slotProps={{ htmlInput: { maxLength: 2000 } }}
    />
  )
}
