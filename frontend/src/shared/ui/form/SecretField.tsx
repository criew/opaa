import { useState, type Ref } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined'
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined'
import { controlHeight, fontSize } from '../../theme/tokens'
import FormField from './FormField'
import FormFieldLabel from './FormFieldLabel'

/** The explicit way to remove a stored secret with the next save. */
export interface SecretRemoval {
  requested: boolean
  onChange: (requested: boolean) => void
  /** The text action beside „Ersetzen“, e.g. „Gespeichertes Passwort löschen“. */
  removeLabel: string
  /** The text action that takes the removal back. */
  keepLabel: string
  /** Said while the stored secret is marked for removal. */
  removedText: string
}

interface SecretFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  /** A secret is stored and still serves: the field reads „Gesetzt“ until it is replaced. */
  stored?: boolean
  error?: string | null
  hint?: string
  optional?: boolean
  autoComplete?: 'new-password' | 'one-time-code' | 'off'
  inputRef?: Ref<HTMLInputElement>
  removal?: SecretRemoval
}

/**
 * Quiet text buttons below a field start with their label on the field's left edge: a narrow
 * inner padding, taken back by the same negative margin, keeps a hover surface around the label.
 */
const EDGE_ALIGNED_SX = { px: 1, ml: -1 } as const

/**
 * The one field for a password, key or token (guidelines 5.2): masked, with an eye that shows
 * what was typed here. A stored secret is never mirrored back; it reads „Gesetzt“ with „Ersetzen“,
 * and only replacing it opens an empty field. Removing a stored secret is a text action at the
 * field. The control that stands for the field carries its id and error in every state, so a
 * form's focus on the first invalid entry lands on it.
 */
export default function SecretField({
  id,
  label,
  value,
  onChange,
  stored = false,
  error,
  hint,
  optional = false,
  autoComplete = 'new-password',
  inputRef,
  removal,
}: SecretFieldProps) {
  const [replacing, setReplacing] = useState(false)
  const [shown, setShown] = useState(false)
  const errorId = `${id}-error`

  function focusControl() {
    // The control of the left state gives way to the next one; the focus follows it.
    requestAnimationFrame(() => document.getElementById(id)?.focus())
  }

  if (stored && removal?.requested) {
    return (
      <Box>
        <FormFieldLabel id={`${id}-label`}>{label}</FormFieldLabel>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 1.5,
            minHeight: controlHeight.regular,
          }}
        >
          <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
            {removal.removedText}
          </Typography>
          <Button
            id={id}
            size="small"
            variant="text"
            onClick={() => {
              removal.onChange(false)
              focusControl()
            }}
          >
            {removal.keepLabel}
          </Button>
        </Box>
      </Box>
    )
  }

  if (stored && !replacing && value === '') {
    return (
      <Box>
        <FormFieldLabel id={`${id}-label`}>{label}</FormFieldLabel>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 1.5,
            minHeight: controlHeight.regular,
          }}
        >
          <Typography sx={{ fontSize: fontSize.text }}>Gesetzt</Typography>
          <Button
            id={id}
            size="small"
            variant="outlined"
            aria-label={`${label} ersetzen`}
            aria-describedby={error ? errorId : undefined}
            data-invalid={error ? 'true' : undefined}
            onClick={() => {
              setReplacing(true)
              focusControl()
            }}
          >
            Ersetzen
          </Button>
          {removal && (
            <Button
              size="small"
              variant="text"
              onClick={() => {
                onChange('')
                removal.onChange(true)
                focusControl()
              }}
            >
              {removal.removeLabel}
            </Button>
          )}
        </Box>
        {error && (
          <Typography id={errorId} sx={{ fontSize: fontSize.meta, color: 'error.main', mt: 0.5 }}>
            {error}
          </Typography>
        )}
      </Box>
    )
  }

  return (
    <Box>
      <FormField
        id={id}
        label={label}
        optional={optional}
        inputRef={inputRef}
        type={shown ? 'text' : 'password'}
        autoComplete={autoComplete}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        error={Boolean(error)}
        helperText={
          // A hint naming where the value is found stays below the error.
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
          htmlInput: { spellCheck: false },
          input: {
            endAdornment: (
              <InputAdornment position="end">
                <Tooltip title={shown ? 'Verbergen' : 'Anzeigen'}>
                  <IconButton
                    size="small"
                    edge="end"
                    aria-label={shown ? `${label} verbergen` : `${label} anzeigen`}
                    aria-pressed={shown}
                    onClick={() => setShown((current) => !current)}
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
          sx={{ mt: 1, ...EDGE_ALIGNED_SX }}
          onClick={() => {
            onChange('')
            setReplacing(false)
            focusControl()
          }}
        >
          Gespeicherten Wert behalten
        </Button>
      )}
    </Box>
  )
}
