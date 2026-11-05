import { useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import FormControlLabel from '@mui/material/FormControlLabel'
import Link from '@mui/material/Link'
import Switch from '@mui/material/Switch'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import type { ServiceAccountResponse } from '@/shared/api/types'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import PageSection from '@/shared/ui/PageSection'
import { ListEmptyState, ListLoading } from '@/shared/ui/list/AdminList'
import { adminTableSx, definitionListSx, listCardSx } from '@/shared/ui/list/adminListStyles'
import ServiceAccountDetailBody from './ServiceAccountDetailBody'
import ServiceAccountFormDialog from './ServiceAccountFormDialog'
import { AccountState, DetailFrame, Ending, ResponsiblePerson } from './ServiceAccountParts'
import { confirmDeactivate, useServiceAccountDetail } from './serviceAccountHooks'
import { useServiceAccounts } from './useServiceAccounts'

export const ACCOUNT_PARAM = 'account'

function NameLink({ account, href }: { account: ServiceAccountResponse; href: string }) {
  return (
    <Link
      component={RouterLink}
      to={href}
      preventScrollReset
      sx={{ fontWeight: 600, overflowWrap: 'anywhere' }}
    >
      {account.name}
    </Link>
  )
}

/** The side panel of one account, opened with `?account=<id>`. */
function ServiceAccountDrawer({
  id,
  onClose,
  onChanged,
}: {
  id: string | null
  onClose: () => void
  onChanged: () => void
}) {
  const { detail, error, reload } = useServiceAccountDetail(id)
  const account = detail?.serviceAccount
  return (
    <Drawer
      anchor="right"
      open={id !== null}
      onClose={onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': 'service-account-panel-title',
          sx: { width: { xs: '100%', sm: 560 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      <DialogTitleWithClose id="service-account-panel-title" onClose={onClose}>
        {account?.name ?? 'Dienstkonto'}
      </DialogTitleWithClose>
      <Box sx={{ px: 3, pb: 3, overflowY: 'auto', flex: 1 }}>
        <DetailFrame error={error} loading={!detail} onRetry={() => void reload()}>
          {detail && (
            <ServiceAccountDetailBody
              detail={detail}
              onChanged={() => {
                void reload()
                onChanged()
              }}
            />
          )}
        </DetailFrame>
      </Box>
      {account?.active && (
        <Box sx={{ px: 3, py: 2, borderTop: 1, borderColor: 'divider' }}>
          <Button
            variant="outlined"
            color="error"
            onClick={() =>
              void confirmDeactivate(account, () => {
                void reload()
                onChanged()
              })
            }
          >
            Deaktivieren
          </Button>
        </Box>
      )}
    </Drawer>
  )
}

/**
 * Variant A: the service accounts as one more list in the tab of the external AI tools, a table
 * like the access tokens below; one account opens in the side panel like the settings.
 */
export default function ServiceAccountsSectionA() {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const [searchParams, setSearchParams] = useSearchParams()
  const { accounts, error, reload } = useServiceAccounts()
  const [creating, setCreating] = useState(false)
  const [showInactive, setShowInactive] = useState(false)
  const openId = searchParams.get(ACCOUNT_PARAM)

  function hrefOf(id: string) {
    const next = new URLSearchParams(searchParams)
    next.set(ACCOUNT_PARAM, id)
    return `?${next.toString()}`
  }

  function setOpen(id: string | null) {
    const next = new URLSearchParams(searchParams)
    if (id) next.set(ACCOUNT_PARAM, id)
    else next.delete(ACCOUNT_PARAM)
    setSearchParams(next, { replace: true, preventScrollReset: true })
  }

  const inactive = (accounts ?? []).filter((account) => !account.active).length
  const shown = (accounts ?? []).filter((account) => account.active || showInactive)

  return (
    <PageSection
      title={accounts && !error ? `Dienstkonten (${accounts.length - inactive})` : 'Dienstkonten'}
      action={
        <Button variant="outlined" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
          Dienstkonto anlegen
        </Button>
      }
    >
      {error ? (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void reload()}>
              Erneut laden
            </Button>
          }
        >
          {error}
        </Alert>
      ) : accounts === null ? (
        <ListLoading label="Dienstkonten werden geladen" />
      ) : accounts.length === 0 ? (
        <ListEmptyState
          title="Noch kein Dienstkonto."
          hint="Ein Dienstkonto gibt einem Fachverfahren einen eigenen, lesenden Zugang."
        />
      ) : (
        <>
          {inactive > 0 && (
            <FormControlLabel
              sx={{ mb: 1 }}
              control={
                <Switch
                  checked={showInactive}
                  onChange={(e) => setShowInactive(e.target.checked)}
                />
              }
              label={`Deaktivierte anzeigen (${inactive})`}
            />
          )}
          {isDesktop ? (
            <TableContainer>
              <Table size="small" aria-label="Dienstkonten" sx={{ ...adminTableSx, minWidth: 680 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    <TableCell sx={{ width: '28%' }}>Verantwortlich</TableCell>
                    <TableCell align="right" sx={{ width: 96 }}>
                      Tokens
                    </TableCell>
                    <TableCell sx={{ width: 168 }}>Nächster Ablauf</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {shown.map((account) => (
                    <TableRow
                      key={account.id}
                      hover
                      onClick={() => setOpen(account.id)}
                      sx={{ cursor: 'pointer' }}
                    >
                      <TableCell>
                        <NameLink account={account} href={hrefOf(account.id)} />
                        {!account.active && (
                          <Box sx={{ fontSize: 12.5 }}>
                            <AccountState account={account} />
                          </Box>
                        )}
                      </TableCell>
                      <TableCell>
                        <ResponsiblePerson account={account} />
                      </TableCell>
                      <TableCell align="right">{account.activeTokenCount}</TableCell>
                      <TableCell>
                        {account.active ? <Ending value={account.nextExpiryAt} /> : '—'}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          ) : (
            <Box
              component="ul"
              aria-label="Dienstkonten"
              sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderColor: 'divider' }}
            >
              {shown.map((account) => (
                <Box component="li" key={account.id} sx={listCardSx}>
                  <Typography component="div" sx={{ fontSize: 13.5 }}>
                    <NameLink account={account} href={hrefOf(account.id)} />
                  </Typography>
                  <Box component="dl" sx={definitionListSx}>
                    <dt>Verantwortlich</dt>
                    <dd>
                      <ResponsiblePerson account={account} />
                    </dd>
                    <dt>Tokens</dt>
                    <dd>{account.activeTokenCount}</dd>
                    <dt>Nächster Ablauf</dt>
                    <dd>{account.active ? <Ending value={account.nextExpiryAt} /> : '—'}</dd>
                    {!account.active && (
                      <>
                        <dt>Zustand</dt>
                        <dd>
                          <AccountState account={account} />
                        </dd>
                      </>
                    )}
                  </Box>
                </Box>
              ))}
            </Box>
          )}
        </>
      )}

      <ServiceAccountFormDialog
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={(saved) => {
          setCreating(false)
          void reload()
          setOpen(saved.id)
        }}
      />
      <ServiceAccountDrawer
        id={openId}
        onClose={() => setOpen(null)}
        onChanged={() => void reload()}
      />
    </PageSection>
  )
}
