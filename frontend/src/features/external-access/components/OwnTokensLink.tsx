import { Link as RouterLink } from 'react-router'
import Link from '@mui/material/Link'
import { OWN_TOKENS_ROUTE } from '@/shared/routes'

/** „Einstellungen → Zugangstokens" as a link to the own tokens - where each person creates hers. */
export default function OwnTokensLink() {
  return (
    <Link component={RouterLink} to={OWN_TOKENS_ROUTE}>
      Einstellungen → Zugangstokens
    </Link>
  )
}
