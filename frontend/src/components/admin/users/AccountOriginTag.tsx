import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined'
import type { AccountResponse } from '../../../types/api'
import { accountOriginLabel, isLocalAccount } from './accountLabels'

/**
 * Die Herkunft eines Kontos (#1601): Schlüssel und „Lokal" für ein Konto dieser Installation,
 * Gebäude und Anbietername für ein Konto eines Identitätsanbieters. Das Symbol trägt keine eigene
 * Bedeutung - das Wort daneben tut es (guidelines 1.2); ein entfernter Anbieter nennt im Tooltip
 * den Issuer, unter dem das Konto entstand.
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
  // Ein gekuerzter Anbietername und der Issuer eines Kontos ohne Anbieterzeile gehoeren beide in
  // den Tooltip - die Zelle ist schmal, die Zuordnung muss trotzdem eindeutig bleiben.
  return <Tooltip title={local ? '' : `${label} · Issuer: ${account.issuer}`}>{tag}</Tooltip>
}
