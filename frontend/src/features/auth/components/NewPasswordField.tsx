import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import AutoFixHighOutlinedIcon from '@mui/icons-material/AutoFixHighOutlined'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import PasswordStrengthMeter from './PasswordStrengthMeter'
import SecretField from '@/shared/ui/form/SecretField'
import { copyToClipboard } from '@/shared/utils/clipboard'
import { generateStrongPassword } from '@/shared/session/passwordStrength'
import { fontSize } from '@/shared/theme/tokens'

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
  /** Lets the form move the focus here - the first field with an error takes it after a refusal. */
  inputRef?: React.Ref<HTMLInputElement>
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
  inputRef,
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
      <SecretField
        id={id}
        label={label}
        value={value}
        onChange={onChange}
        disabled={disabled}
        error={errorMessage}
        inputRef={inputRef}
        shown={visible}
        onShownChange={setVisible}
      />
      <PasswordStrengthMeter password={value} minLength={minLength} />
      <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
        <Button
          type="button"
          size="small"
          startIcon={<AutoFixHighOutlinedIcon />}
          onClick={generate}
          disabled={disabled}
          sx={{ fontSize: fontSize.meta }}
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
            sx={{ fontSize: fontSize.meta }}
          >
            Kopieren
          </Button>
        )}
      </Stack>
    </Box>
  )
}
