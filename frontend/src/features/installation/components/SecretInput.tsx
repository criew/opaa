import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined'
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

interface SecretInputProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  /** A value is stored: the field reads „Gesetzt" with „Ersetzen" until it is replaced. */
  stored?: boolean
  error?: string
  hint?: string
  optional?: boolean
  autoComplete: 'new-password' | 'off'
  inputRef?: React.Ref<HTMLInputElement>
}

/**
 * A password, token or key. A stored secret is never mirrored back (guidelines 6, „Gesetzt ·
 * Ersetzen"); replacing it opens an empty field. The show/hide toggle is announced by what it
 * does next and stays in the tab order, so a long pasted value can be checked by keyboard.
 */
export default function SecretInput({
  id,
  label,
  value,
  onChange,
  stored = false,
  error,
  hint,
  optional = false,
  autoComplete,
  inputRef,
}: SecretInputProps) {
  const [replacing, setReplacing] = useState(false)
  const [shown, setShown] = useState(false)
  const toggleLabel = shown ? `${label} verbergen` : `${label} anzeigen`

  if (stored && !replacing) {
    return (
      <Box>
        <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}>
          <Typography sx={{ fontSize: 13.5 }}>Gesetzt</Typography>
          <Button
            size="small"
            variant="outlined"
            aria-label={`${label} ersetzen`}
            onClick={() => {
              setReplacing(true)
              // The button gives way to the field; the focus follows it.
              requestAnimationFrame(() => document.getElementById(id)?.focus())
            }}
          >
            Ersetzen
          </Button>
        </Box>
        {error && (
          <Typography role="alert" sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>
            {error}
          </Typography>
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
        inputRef={inputRef}
        type={shown ? 'text' : 'password'}
        size="small"
        fullWidth
        autoComplete={autoComplete}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        error={Boolean(error)}
        helperText={error ?? hint}
        slotProps={{
          htmlInput: { spellCheck: false },
          input: {
            endAdornment: (
              <InputAdornment position="end">
                <Tooltip title={shown ? 'Verbergen' : 'Anzeigen'}>
                  <IconButton
                    size="small"
                    edge="end"
                    aria-label={toggleLabel}
                    onClick={() => setShown((s) => !s)}
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
      {stored && (
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
