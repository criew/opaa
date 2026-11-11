import type { SxProps, Theme } from '@mui/material/styles'
import { TextLink } from '@/shared/ui/DetailSection'
import { LOGIN_ROUTE } from '@/shared/routes'

/** The wording of the way back to the sign-in, the same on every page before the session. */
export const SIGN_IN_LINK_LABEL = 'Zur Anmeldung'

/**
 * The way back to the sign-in (guidelines 5.1): one wording, a text link below the form or below
 * the outcome of a step. `sx` places it only.
 */
export default function SignInLink({ sx }: { sx?: SxProps<Theme> }) {
  return (
    <TextLink to={LOGIN_ROUTE} sx={sx}>
      {SIGN_IN_LINK_LABEL}
    </TextLink>
  )
}
