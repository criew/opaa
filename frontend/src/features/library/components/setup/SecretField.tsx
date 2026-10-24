import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined'
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

interface SecretFieldProps {
  id: string
  label: string
  value: string
  onChange: (next: string) => void
  /** A secret is stored and still serves: the field shows „gesetzt“ until it is replaced. */
  stored: boolean
  error?: string | null
  hint?: string
  optional?: boolean
}

/**
 * The one field for a password, token or key. A stored secret is never mirrored back: it reads
 * „gesetzt“ with „Ersetzen“, and only replacing it opens an empty field. Pasting and showing the
 * typed value are allowed.
 */
export default function SecretField({
  id,
  label,
  value,
  onChange,
  stored,
  error,
  hint,
  optional = false,
}: SecretFieldProps) {
  const [replacing, setReplacing] = useState(false)
  const [shown, setShown] = useState(false)
  const helperId = `${id}-helper`

  if (stored && !replacing && value === '') {
    return (
      <Box>
        <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}>
          <Typography sx={{ fontSize: 13.5 }} aria-describedby={`${id}-label`}>
            gesetzt
          </Typography>
          <Button
            size="small"
            variant="outlined"
            id={id}
            onClick={() => {
              setReplacing(true)
              // the button gives way to the field; the focus follows it
              requestAnimationFrame(() => document.getElementById(id)?.focus())
            }}
            aria-label={`${label} ersetzen`}
          >
            Ersetzen
          </Button>
        </Box>
        {error && (
          <Typography sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>{error}</Typography>
        )}
      </Box>
    )
  }

  return (
    <Box>
      <FieldLabel htmlFor={id}>
        {label}
        {optional ? ' (optional)' : ''}
      </FieldLabel>
      <TextField
        id={id}
        type={shown ? 'text' : 'password'}
        size="small"
        fullWidth
        autoComplete="new-password"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        error={Boolean(error)}
        helperText={error ?? hint}
        slotProps={{
          formHelperText: { id: helperId },
          input: {
            endAdornment: (
              <InputAdornment position="end">
                <Tooltip title={shown ? 'Verbergen' : 'Anzeigen'}>
                  <IconButton
                    size="small"
                    edge="end"
                    onClick={() => setShown((s) => !s)}
                    aria-label={shown ? `${label} verbergen` : `${label} anzeigen`}
                  >
                    {shown ? (
                      <VisibilityOffOutlined fontSize="small" />
                    ) : (
                      <VisibilityOutlined fontSize="small" />
                    )}
                  </IconButton>
                </Tooltip>
              </InputAdornment>
            ),
          },
        }}
      />
      {stored && replacing && (
        <Button
          size="small"
          variant="text"
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
