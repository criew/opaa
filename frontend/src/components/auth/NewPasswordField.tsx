import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import AutoFixHighOutlinedIcon from '@mui/icons-material/AutoFixHighOutlined'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import PasswordField from './PasswordField'
import PasswordStrengthMeter from './PasswordStrengthMeter'
import { copyToClipboard } from '../../utils/clipboard'
import { generateStrongPassword } from '../../utils/passwordStrength'

interface NewPasswordFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  /** From `GET /api/v1/auth/config`; the generator and the meter both read it. */
  minLength: number
  disabled?: boolean
  errorMessage?: string
  /**
   * Runs in addition to {@link onChange} when the generator filled the field - the one way a page
   * with a repeat field can keep it in step with a password nobody typed.
   */
  onGenerated?: (value: string) => void
}

/**
 * A field for a password that is being chosen: the input, the strength estimate and the two
 * self-service actions the policy's renunciation of complexity rules calls for (ADR-0033,
 * Entscheidung 9) - generating one that meets the rule by construction, and copying it. Generating
 * reveals the entry, because a password nobody can read is one nobody can note down.
 */
export default function NewPasswordField({
  id,
  label,
  value,
  onChange,
  minLength,
  disabled = false,
  errorMessage,
  onGenerated,
}: NewPasswordFieldProps) {
  const [visible, setVisible] = useState(false)

  function generate() {
    const generated = generateStrongPassword(minLength)
    onChange(generated)
    onGenerated?.(generated)
    setVisible(true)
  }

  return (
    <Box>
      <PasswordField
        id={id}
        label={label}
        value={value}
        onChange={onChange}
        autoComplete="new-password"
        disabled={disabled}
        errorMessage={errorMessage}
        visible={visible}
        onVisibleChange={setVisible}
      />
      <PasswordStrengthMeter password={value} minLength={minLength} id={`${id}-strength`} />
      <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
        <Button
          type="button"
          size="small"
          startIcon={<AutoFixHighOutlinedIcon />}
          onClick={generate}
          disabled={disabled}
          sx={{ fontSize: 12.5 }}
        >
          Sicheres Passwort erzeugen
        </Button>
        {value.length > 0 && (
          <Button
            type="button"
            size="small"
            startIcon={<ContentCopyOutlinedIcon />}
            onClick={() => void copyToClipboard(value, 'Passwort')}
            disabled={disabled}
            sx={{ fontSize: 12.5 }}
          >
            Kopieren
          </Button>
        )}
      </Stack>
    </Box>
  )
}
