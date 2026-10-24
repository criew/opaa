import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontFamily } from '@/shared/theme/tokens'

interface SetupTextFieldProps {
  id: string
  label: string
  value: string
  onChange: (next: string) => void
  onBlur?: () => void
  error?: string | null
  hint?: string
  placeholder?: string
  optional?: boolean
  /** Paths, addresses and keys are set in the mono face. */
  mono?: boolean
  multiline?: boolean
  readOnly?: boolean
  /** The expected length: a region is short, an address long. */
  width?: 'short' | 'full'
}

/** The text field of every setup row: label above, hint or error below, nothing floating. */
export default function SetupTextField({
  id,
  label,
  value,
  onChange,
  onBlur,
  error,
  hint,
  placeholder,
  optional = false,
  mono = false,
  multiline = false,
  readOnly = false,
  width = 'full',
}: SetupTextFieldProps) {
  return (
    <Box sx={{ maxWidth: width === 'short' ? 240 : undefined }}>
      <FieldLabel htmlFor={id}>
        {label}
        {optional ? ' (optional)' : ''}
      </FieldLabel>
      <TextField
        id={id}
        size="small"
        fullWidth
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        error={Boolean(error)}
        helperText={
          // a hint naming where the value is found stays below the error
          error && hint ? (
            <>
              {error}
              <Box component="span" sx={{ display: 'block', color: 'text.secondary' }}>
                {hint}
              </Box>
            </>
          ) : (
            (error ?? hint)
          )
        }
        placeholder={placeholder}
        multiline={multiline}
        minRows={multiline ? 2 : undefined}
        maxRows={multiline ? 6 : undefined}
        slotProps={{
          input: { readOnly },
          htmlInput: {
            spellCheck: mono ? false : undefined,
            style: mono ? { fontFamily: fontFamily.mono, fontSize: 13 } : undefined,
          },
        }}
      />
    </Box>
  )
}
