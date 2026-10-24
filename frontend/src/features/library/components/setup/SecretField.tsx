import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import SourceField from './SourceField'

interface SecretFieldProps {
  id: string
  field: string
  label: string
  value: string
  onChange: (next: string) => void
  /** A secret is stored and still serves the entered address: shown as „gesetzt", not as a field. */
  stored: boolean
  hint?: string
}

/**
 * A password, token or key of a source. A stored one is never shown or counted out in dots: it
 * reads „gesetzt" with „Ersetzen"; replacing opens the field, „Behalten" closes it again and
 * drops what was typed.
 */
export default function SecretField({
  id,
  field,
  label,
  value,
  onChange,
  stored,
  hint,
}: SecretFieldProps) {
  const [replacing, setReplacing] = useState(value !== '')
  if (stored && !replacing) {
    return (
      <Box>
        <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}>
          <Typography sx={{ fontSize: 14 }} aria-labelledby={`${id}-label`}>
            gesetzt
          </Typography>
          <Button
            size="small"
            onClick={() => setReplacing(true)}
            aria-label={`${label} ersetzen`}
          >
            Ersetzen
          </Button>
        </Box>
      </Box>
    )
  }
  return (
    <Box>
      <SourceField
        id={id}
        field={field}
        label={label}
        type="password"
        value={value}
        onChange={onChange}
        autoComplete="new-password"
        hint={hint}
      />
      {stored && (
        <Button
          size="small"
          sx={{ mt: 0.5 }}
          onClick={() => {
            onChange('')
            setReplacing(false)
          }}
        >
          Gespeicherten Wert behalten
        </Button>
      )}
    </Box>
  )
}
