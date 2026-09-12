import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { passwordStrength } from '../../utils/passwordStrength'

// Four steps, the rating's own colour. "schwach" is the one that may use the danger role: it says
// the entry is below the rule shown above it, which the backend will refuse.
const STEP_COLOR = [
  'transparent',
  'error.main',
  'warning.main',
  'text.secondary',
  'success.main',
] as const

interface PasswordStrengthMeterProps {
  password: string
  minLength: number
}

/**
 * The strength estimate as four steps plus its word (ADR-0033, Entscheidung 9). Orientation, not a
 * gate: the bar never blocks the submit, because the backend's policy is what decides and a "stark"
 * password can still be on the list of the most common ones. The word carries the meaning for
 * anyone who does not see the colours, and it is a polite live region so a change of rating is
 * announced while typing; the bar itself is decorative and hidden from assistive technology.
 */
export default function PasswordStrengthMeter({ password, minLength }: PasswordStrengthMeterProps) {
  if (password.length === 0) return null
  const { score, label } = passwordStrength(password, minLength)

  return (
    <Box
      sx={{ display: 'flex', alignItems: 'center', gap: 1.25, mt: 1 }}
      data-testid="password-strength"
    >
      <Box aria-hidden="true" sx={{ display: 'flex', gap: 0.5, flex: 1 }}>
        {[1, 2, 3, 4].map((step) => (
          <Box
            key={step}
            sx={{
              height: 4,
              flex: 1,
              borderRadius: 999,
              bgcolor: step <= score ? STEP_COLOR[score] : 'divider',
              transition: 'background-color 200ms ease-out',
            }}
          />
        ))}
      </Box>
      <Typography
        aria-live="polite"
        sx={{ fontSize: 11.5, fontWeight: 500, color: 'text.secondary', minWidth: 78 }}
      >
        {`Stärke: ${label}`}
      </Typography>
    </Box>
  )
}
