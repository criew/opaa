import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined'
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined'
import FormFieldLabel from './FormFieldLabel'
import FormField from './FormField'
import { controlHeight, fontSize } from '../../theme/tokens'

interface SecretFieldProps {
  id: string
  label: string
  /**
   * What the toggle calls its field, where the label is a phrase that reads badly in front of
   * „anzeigen“ (a repetition field: „Wiederholung“). Defaults to the label.
   */
  toggleName?: string
  value: string
  onChange: (next: string) => void
  /** A secret is stored and still serves: the field shows „gesetzt“ until it is replaced. */
  stored?: boolean
  error?: string | null
  hint?: string
  optional?: boolean
  /** `current-password` for the mask of a sign-in; a secret that is being set keeps the default. */
  autoComplete?: 'new-password' | 'current-password'
  disabled?: boolean
  inputRef?: React.Ref<HTMLInputElement>
  /** Reveals the entry from outside, e.g. after a generator filled the field. */
  shown?: boolean
  onShownChange?: (shown: boolean) => void
}

/**
 * The one field for a password, token or key. A stored secret is never mirrored back: it reads
 * „gesetzt“ with „Ersetzen“, and only replacing it opens an empty field. Pasting and showing the
 * typed value are allowed; the toggle names its field and what the next activation does, and it
 * stays in the tab order so that revealing a long value is not a mouse-only action.
 */
export default function SecretField({
  id,
  label,
  toggleName = label,
  value,
  onChange,
  stored = false,
  error,
  hint,
  optional = false,
  autoComplete = 'new-password',
  disabled = false,
  inputRef,
  shown: controlledShown,
  onShownChange,
}: SecretFieldProps) {
  const [replacing, setReplacing] = useState(false)
  const [ownShown, setOwnShown] = useState(false)
  const shown = controlledShown ?? ownShown

  function toggleShown() {
    const next = !shown
    setOwnShown(next)
    onShownChange?.(next)
  }

  if (stored && !replacing && value === '') {
    return (
      <Box>
        <FormFieldLabel id={`${id}-label`}>{label}</FormFieldLabel>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: controlHeight.regular }}
        >
          <Typography sx={{ fontSize: fontSize.text }} aria-describedby={`${id}-label`}>
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
          <Typography sx={{ fontSize: fontSize.meta, color: 'error.main', mt: 0.5 }}>
            {error}
          </Typography>
        )}
      </Box>
    )
  }

  return (
    <Box>
      <FormField
        label={label}
        optional={optional}
        id={id}
        inputRef={inputRef}
        type={shown ? 'text' : 'password'}
        size="small"
        fullWidth
        autoComplete={autoComplete}
        disabled={disabled}
        value={value}
        onChange={(e) => onChange(e.target.value)}
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
        slotProps={{
          input: {
            endAdornment: (
              <InputAdornment position="end">
                <Tooltip title={shown ? 'Verbergen' : 'Anzeigen'}>
                  <IconButton
                    size="small"
                    edge="end"
                    onClick={toggleShown}
                    aria-label={shown ? `${toggleName} verbergen` : `${toggleName} anzeigen`}
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
