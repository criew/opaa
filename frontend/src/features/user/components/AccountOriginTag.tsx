import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined'
import type { AccountResponse } from '@/shared/api/types'
import { accountOriginLabel, isLocalAccount } from './accountLabels'

/**
 * An account's origin: key and „Lokal" for an account of this installation,
 * building and provider name for an account of an identity provider. The icon carries no meaning
 * of its own - the word next to it does (guidelines 1.2); a removed provider names in the tooltip
 * the issuer under which the account was created.
 */
export default function AccountOriginTag({ account }: { account: AccountResponse }) {
  const local = isLocalAccount(account)
  const label = accountOriginLabel(account)
  const Icon = local ? KeyOutlinedIcon : CorporateFareOutlinedIcon
  const tag = (
    <Stack
      direction="row"
      spacing={0.5}
      component="span"
      sx={{ alignItems: 'center', display: 'inline-flex', minWidth: 0, maxWidth: '100%' }}
    >
      <Icon
        aria-hidden="true"
        sx={{ fontSize: 15, flex: 'none', color: local ? 'primary.main' : 'text.secondary' }}
      />
      <Typography
        component="span"
        sx={{
          fontSize: 12.5,
          fontWeight: local ? 600 : 500,
          color: local ? 'text.primary' : 'text.secondary',
          minWidth: 0,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {label}
      </Typography>
    </Stack>
  )
  // A truncated provider name and the issuer of an account without a provider row both belong in
  // the tooltip - the cell is narrow, the association must still stay unambiguous.
  return <Tooltip title={local ? '' : `${label} · Issuer: ${account.issuer}`}>{tag}</Tooltip>
}
