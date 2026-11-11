import type { KeyboardEventHandler, ReactNode, Ref } from 'react'
import FormField from '../form/FormField'

interface SetupFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  /** The field error; replaces the hint and marks the input invalid (guidelines 5.2). */
  error?: string
  hint?: string
  optional?: boolean
  type?: 'text' | 'email'
  autoComplete?: string
  inputMode?: 'text' | 'url' | 'email' | 'numeric' | 'decimal'
  /** A field holding only a number: 160 px wide, beside its pair with `fullWidth={false}`. */
  numeric?: boolean
  fullWidth?: boolean
  placeholder?: string
  disabled?: boolean
  inputRef?: Ref<HTMLInputElement>
  maxLength?: number
  multiline?: boolean
  onKeyDown?: KeyboardEventHandler<HTMLDivElement>
  /** Shown inside the field's right edge, e.g. a colour picker beside a hex value. */
  endAdornment?: ReactNode
}

/**
 * A text field of a set-up form, in the assistant and the administration alike: `FormField` with
 * the value as text and the error as its sentence, so that a field block reads one line per rule.
 */
export default function SetupField({
  value,
  onChange,
  error,
  hint,
  inputMode,
  maxLength,
  endAdornment,
  ...rest
}: SetupFieldProps) {
  return (
    <FormField
      {...rest}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      error={Boolean(error)}
      helperText={error ?? hint}
      slotProps={{
        htmlInput: { inputMode, spellCheck: false, maxLength },
        input: endAdornment ? { endAdornment } : undefined,
      }}
    />
  )
}
