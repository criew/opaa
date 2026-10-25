import Box from '@mui/material/Box'
import TextField, { type TextFieldProps } from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

interface FormFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  /** The field error, shown below the field and tied to it (guidelines 5.2). */
  error?: string
  hint?: string
  optional?: boolean
  type?: TextFieldProps['type']
  autoComplete?: string
  inputMode?: 'text' | 'url' | 'email' | 'numeric' | 'decimal'
  disabled?: boolean
  inputRef?: React.Ref<HTMLInputElement>
}

/**
 * A text input of the assistant: label above the field, „(optional)" on optional fields only,
 * error or hint below. MUI ties the helper text to the input via `aria-describedby` and marks
 * the input `aria-invalid` while an error stands.
 */
export default function FormField({
  id,
  label,
  value,
  onChange,
  error,
  hint,
  optional = false,
  type = 'text',
  autoComplete,
  inputMode,
  disabled,
  inputRef,
}: FormFieldProps) {
  return (
    <Box>
      <FieldLabel htmlFor={id}>
        {label}
        {optional ? ' (optional)' : ''}
      </FieldLabel>
      <TextField
        id={id}
        inputRef={inputRef}
        type={type}
        size="small"
        fullWidth
        autoComplete={autoComplete}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        error={Boolean(error)}
        helperText={error ?? hint}
        disabled={disabled}
        slotProps={{ htmlInput: { inputMode, spellCheck: false } }}
      />
    </Box>
  )
}
