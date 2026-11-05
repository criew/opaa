import { useState } from 'react'
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import type { ServiceAccountResponse } from '@/shared/api/types'
import DetailSection, { TextLink } from '@/shared/ui/DetailSection'
import { ListEmptyState, ListLoading } from '@/shared/ui/list/AdminList'
import ServiceAccountDetailBody from './ServiceAccountDetailBody'
import ServiceAccountFormDialog from './ServiceAccountFormDialog'
import { AccountState, DetailFrame } from './ServiceAccountParts'
import { confirmDeactivate, useServiceAccountDetail } from './serviceAccountHooks'
import { MISSING_PERSON, formatDate, soonLabel, tokenCountLabel } from './serviceAccountLabels'
import { useServiceAccounts } from './useServiceAccounts'
import StateDot from '@/shared/ui/StateDot'

const ACCOUNT_PARAM = 'account'

/** The second line of a row: person · tokens · next end - or why it needs attention. */
function RowFacts({ account }: { account: ServiceAccountResponse }) {
  if (!account.active) {
    return <AccountState account={account} />
  }
  const soon = soonLabel(account.nextExpiryAt)
  const parts = [
    tokenCountLabel(account.activeTokenCount),
    account.nextExpiryAt
      ? `nächster Ablauf ${formatDate(account.nextExpiryAt)}${soon ? ` (${soon})` : ''}`
      : null,
  ].filter(Boolean)
  return (
    <Box component="span" sx={{ display: 'inline-flex', gap: 1, flexWrap: 'wrap' }}>
      {account.responsiblePersonMissing ? (
        <StateDot tone="warning" label={MISSING_PERSON} />
      ) : (
        <span>{account.responsibleDisplayName}</span>
      )}
      <span aria-hidden="true">·</span>
      <span>{parts.join(' · ')}</span>
    </Box>
  )
}

function AccountRow({ account, href }: { account: ServiceAccountResponse; href: string }) {
  return (
    <Box component="li" sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Box
        component={RouterLink}
        to={href}
        sx={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) auto',
          alignItems: 'center',
          columnGap: 1.5,
          py: 1.25,
          px: 1,
          mx: -1,
          color: 'text.primary',
          textDecoration: 'none',
          borderRadius: 1,
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
            {account.name}
          </Typography>
          <Typography component="div" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            <RowFacts account={account} />
          </Typography>
        </Box>
        <ChevronRightIcon fontSize="small" sx={{ color: 'text.secondary' }} aria-hidden="true" />
      </Box>
    </Box>
  )
}

function DetailView({ id, onChanged }: { id: string; onChanged: () => void }) {
  const { detail, error, reload } = useServiceAccountDetail(id)
  const account = detail?.serviceAccount
  return (
    <Box>
      <Box sx={{ mb: 2 }}>
        <TextLink to="?">Alle Dienstkonten</TextLink>
      </Box>
      <DetailFrame error={error} loading={!detail} onRetry={() => void reload()}>
        {detail && account && (
          <>
            <Typography
              component="h2"
              sx={{ fontSize: 22, fontWeight: 600, mb: 2, overflowWrap: 'anywhere' }}
            >
              {account.name}
            </Typography>

            <ServiceAccountDetailBody
              detail={detail}
              headingLevel="h3"
              onChanged={() => {
                void reload()
                onChanged()
              }}
            />
            {account.active && (
              <DetailSection title="Deaktivieren" headingLevel="h3">
                <Typography
                  sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5, maxWidth: '70ch' }}
                >
                  Widerruft alle Tokens sofort; das Dienstkonto bleibt zur Nachvollziehbarkeit
                  erhalten.
                </Typography>
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
                  Dienstkonto deaktivieren
                </Button>
              </DetailSection>
            )}
          </>
        )}
      </DetailFrame>
    </Box>
  )
}

/**
 * Variant B: the service accounts as a tab of their own in „Anbindungen“. The list leads to a
 * detail view inside the tab (`?account=<id>`), with its own address and „Zurück“.
 */
export default function ServiceAccountsTabB() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { accounts, error, reload } = useServiceAccounts()
  const [creating, setCreating] = useState(false)
  const [showInactive, setShowInactive] = useState(false)
  const openId = searchParams.get(ACCOUNT_PARAM)

  if (openId) return <DetailView id={openId} onChanged={() => void reload()} />

  const inactive = (accounts ?? []).filter((account) => !account.active).length
  const shown = (accounts ?? []).filter((account) => account.active || showInactive)

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          gap: 2,
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          mb: 2.5,
        }}
      >
        <Typography
          sx={{ fontSize: 13.5, color: 'text.secondary', maxWidth: 640, flex: '1 1 320px' }}
        >
          Eigene Zugänge für Fachverfahren, unabhängig von einzelnen Beschäftigten. Welche
          Bibliotheken ein Dienstkonto sieht, entscheidet die Verwaltung der Bibliothek.
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
          Dienstkonto anlegen
        </Button>
      </Box>

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
          <Box
            component="ul"
            aria-label="Dienstkonten"
            sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderColor: 'divider' }}
          >
            {shown.map((account) => (
              <AccountRow
                key={account.id}
                account={account}
                href={`?${ACCOUNT_PARAM}=${account.id}`}
              />
            ))}
          </Box>
        </>
      )}

      <ServiceAccountFormDialog
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={(saved) => {
          setCreating(false)
          void reload()
          void navigate(`?${ACCOUNT_PARAM}=${saved.id}`)
        }}
      />
    </>
  )
}
