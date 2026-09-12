import { useState } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined'
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined'
import FieldLabel from '../wizard/FieldLabel'

interface PasswordFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  /** `current-password` for the mask of a sign-in, `new-password` everywhere else. */
  autoComplete: 'current-password' | 'new-password'
  disabled?: boolean
  /** The field error from `fieldErrors` of the backend, shown below the field (guidelines 5.2). */
  errorMessage?: string
  /** Further text below the field while there is no error. */
  helperText?: string
  inputRef?: React.Ref<HTMLInputElement>
  /** Reveals the entry from outside - what "Sicheres Passwort erzeugen" needs to show its result. */
  visible?: boolean
  onVisibleChange?: (visible: boolean) => void
}

/**
 * The password input of every self-service screen: label above the field (guidelines 5.2) and a
 * show/hide toggle, which is what makes a long generated password typable at all. The toggle is
 * announced by what it does next, and it stays out of the tab order - a reader moves from the field
 * to the next field, not through a control that only ever reveals what they just typed.
 */
export default function PasswordField({
  id,
  label,
  value,
  onChange,
  autoComplete,
  disabled = false,
  errorMessage,
  helperText,
  inputRef,
  visible: controlledVisible,
  onVisibleChange,
}: PasswordFieldProps) {
  const [ownVisible, setOwnVisible] = useState(false)
  const visible = controlledVisible ?? ownVisible
  const toggleLabel = visible ? 'Passwort verbergen' : 'Passwort anzeigen'

  function toggle() {
    const next = !visible
    setOwnVisible(next)
    onVisibleChange?.(next)
  }

  return (
    <Box>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <TextField
        id={id}
        inputRef={inputRef}
        type={visible ? 'text' : 'password'}
        size="small"
        fullWidth
        autoComplete={autoComplete}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        error={Boolean(errorMessage)}
        helperText={errorMessage ?? helperText}
        slotProps={{
          input: {
            endAdornment: (
              <InputAdornment position="end">
                <Tooltip title={toggleLabel}>
                  <IconButton
                    aria-label={toggleLabel}
                    edge="end"
                    size="small"
                    tabIndex={-1}
                    onClick={toggle}
                  >
                    {visible ? (
                      <VisibilityOffOutlinedIcon fontSize="small" />
                    ) : (
                      <VisibilityOutlinedIcon fontSize="small" />
                    )}
                  </IconButton>
                </Tooltip>
              </InputAdornment>
            ),
          },
        }}
      />
    </Box>
  )
}
