import { useState } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import ManageAccountsOutlinedIcon from '@mui/icons-material/ManageAccountsOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import type { AccountResponse } from '../../../types/api'
import { providerLifecycleHint, roleManagedByProviderText } from './accountLabels'

interface ProviderAccountRowMenuProps {
  account: AccountResponse
  onChangeRole: (account: AccountResponse) => void
}

/**
 * Das Zeilenmenü eines Anbieterkontos (#1601). Es bietet genau das, was OPAA an einem solchen
 * Konto tut - die Rolle (ADR-0033, Entscheidung 11: ein Rollenendpunkt für beide Kontotypen) -
 * und sagt, was beim Anbieter geschieht: Sperren, Befristen und Löschen kennt das Backend für
 * Anbieterkonten nicht, und ein Menü darf keine Handlung vortäuschen, die es nicht gibt.
 */
export default function ProviderAccountRowMenu({
  account,
  onChangeRole,
}: ProviderAccountRowMenuProps) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const close = () => setAnchor(null)
  const reasonId = `account-${account.id}-menu-reason`
  const roleDisabled = account.roleManagedByProvider
  const name = account.displayName ?? account.email ?? account.id

  return (
    <>
      <Tooltip title="Weitere Aktionen">
        <span>
          <IconButton
            size="small"
            aria-label={`Aktionen für „${name}“`}
            aria-haspopup="true"
            onClick={(e) => setAnchor(e.currentTarget)}
          >
            <MoreVertIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={close}
        slotProps={{ list: { 'aria-label': `Aktionen für „${name}“` } }}
      >
        <MenuItem
          onClick={() => {
            close()
            onChangeRole(account)
          }}
          disabled={roleDisabled}
          // Same pattern as LocalUserRowMenu: no wrapper inside the menu, the reason hangs on the
          // entry itself and is read out through the presentation item below.
          title={roleDisabled ? roleManagedByProviderText(account) : undefined}
          aria-describedby={roleDisabled ? reasonId : undefined}
        >
          <ListItemIcon>
            <ManageAccountsOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Rolle ändern …
        </MenuItem>
        <MenuItem component={RouterLink} to="/admin/identity-providers" onClick={close}>
          <ListItemIcon>
            <CorporateFareOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Anbieter verwalten
        </MenuItem>
        <Divider />
        <Box component="li" role="presentation" id={reasonId} sx={{ px: 2, py: 1, maxWidth: 320 }}>
          <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
            {roleDisabled ? roleManagedByProviderText(account) + ' ' : ''}
            {providerLifecycleHint(account)}
          </Typography>
        </Box>
      </Menu>
    </>
  )
}
