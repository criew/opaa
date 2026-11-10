import { useMemo, useState } from 'react'
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
import DetailSection, { TextAction, TextLink } from '@/shared/ui/DetailSection'
import ListSearch from '@/shared/ui/ListSearch'
import { ListEmptyState, ListLoading } from '@/shared/ui/list/AdminList'
import StateDot from '@/shared/ui/StateDot'
import IssueServiceAccountTokenDialog from './IssueServiceAccountTokenDialog'
import ServiceAccountDetailBody from './ServiceAccountDetailBody'
import ServiceAccountFormDialog from './ServiceAccountFormDialog'
import { AccountState, DetailFrame } from './ServiceAccountParts'
import { confirmDeactivate, useServiceAccountDetail } from './serviceAccountHooks'
import { MISSING_PERSON, formatDate, soonLabel, tokenCountLabel } from './serviceAccountLabels'
import { useServiceAccounts } from './useServiceAccounts'
import { fontSize } from '@/shared/theme/tokens'

/** The query parameter that opens one account in the tab, so that the detail has an address. */
const ACCOUNT_PARAM = 'account'

/** From this many accounts on the list gets a search field (guidelines 5.11). */
const SEARCH_THRESHOLD = 8

function matches(account: ServiceAccountResponse, needle: string): boolean {
  return [account.name, account.description, account.responsibleDisplayName].some((value) =>
    value?.toLocaleLowerCase('de').includes(needle),
  )
}

/** The second line of a row: person · tokens · next end, or the state of a deactivated one. */
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
        <Box component="span" sx={{ display: 'inline-flex', gap: 0.75 }}>
          <span>Verantwortlich</span>
          <StateDot tone="warning" label={MISSING_PERSON} />
        </Box>
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
          <Typography sx={{ fontSize: fontSize.text, fontWeight: 600, overflowWrap: 'anywhere' }}>
            {account.name}
          </Typography>
          <Typography component="div" sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
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
  const changed = () => {
    void reload()
    onChanged()
  }
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
              sx={{
                fontSize: fontSize.detailTitle,
                fontWeight: 600,
                mb: 2,
                overflowWrap: 'anywhere',
              }}
            >
              {account.name}
            </Typography>

            <ServiceAccountDetailBody detail={detail} headingLevel="h3" onChanged={changed} />
            {account.active && (
              <DetailSection title="Deaktivieren" headingLevel="h3">
                <Typography
                  sx={{
                    fontSize: fontSize.text,
                    color: 'text.secondary',
                    mb: 1.5,
                    maxWidth: '70ch',
                  }}
                >
                  Widerruft alle Tokens sofort; das Dienstkonto bleibt zur Nachvollziehbarkeit
                  erhalten.
                </Typography>
                <Button
                  variant="outlined"
                  color="error"
                  onClick={() => void confirmDeactivate(account, changed)}
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
 * The tab „Dienstkonten“ in „Anbindungen“: a list with one chevron row per account, from eight
 * accounts on with a search, and a detail view inside the tab (`?account=<id>`). Creating an
 * account leads straight to its first token; „Ohne Token beenden“ leaves it without one.
 */
export default function ServiceAccountsTab() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { accounts, error, reload } = useServiceAccounts()
  const [creating, setCreating] = useState(false)
  const [firstTokenFor, setFirstTokenFor] = useState<ServiceAccountResponse | null>(null)
  const [detailVersion, setDetailVersion] = useState(0)
  const [showInactive, setShowInactive] = useState(false)
  const [query, setQuery] = useState('')
  const openId = searchParams.get(ACCOUNT_PARAM)

  const all = useMemo(() => accounts ?? [], [accounts])
  const inactive = all.filter((account) => !account.active).length
  const needle = query.trim().toLocaleLowerCase('de')
  const shown = all
    .filter((account) => account.active || showInactive)
    .filter((account) => !needle || matches(account, needle))

  const firstTokenDialog = (
    <IssueServiceAccountTokenDialog
      account={firstTokenFor}
      title="Erstes Token ausstellen"
      onClose={() => setFirstTokenFor(null)}
      onIssued={() => {
        void reload()
        setDetailVersion((value) => value + 1)
      }}
    />
  )

  const list = (
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
          sx={{
            fontSize: fontSize.text,
            color: 'text.secondary',
            maxWidth: 640,
            flex: '1 1 320px',
          }}
        >
          Eigene, lesende Zugänge für Verfahren, unabhängig von einzelnen Beschäftigten. Welche
          Bibliotheken ein Dienstkonto sieht, entscheidet die Verwaltung der Bibliothek.
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setCreating(true)}
          disabled={Boolean(error)}
        >
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
          hint="Ein Dienstkonto gibt einem Verfahren einen eigenen, lesenden Zugang."
        />
      ) : (
        <>
          {all.length >= SEARCH_THRESHOLD && (
            <Box sx={{ mb: 1.5, maxWidth: 360 }}>
              <ListSearch
                value={query}
                onChange={setQuery}
                placeholder="Name oder Person"
                label="Dienstkonten durchsuchen"
              />
            </Box>
          )}
          {inactive > 0 && (
            <FormControlLabel
              sx={{ mb: 1 }}
              control={
                <Switch
                  checked={showInactive}
                  onChange={(event) => setShowInactive(event.target.checked)}
                />
              }
              label={`Deaktivierte anzeigen (${inactive})`}
            />
          )}
          {shown.length === 0 ? (
            <Box sx={{ py: 1.5 }}>
              <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 0.5 }}>
                {needle ? `Keine Treffer für „${query.trim()}“.` : 'Kein aktives Dienstkonto.'}
              </Typography>
              {needle && <TextAction onClick={() => setQuery('')}>Suche zurücksetzen</TextAction>}
            </Box>
          ) : (
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
          )}
        </>
      )}

      <ServiceAccountFormDialog
        open={creating}
        createLabel="Weiter"
        onClose={() => setCreating(false)}
        onSaved={(saved) => {
          setCreating(false)
          void reload()
          void navigate(`?${ACCOUNT_PARAM}=${saved.id}`)
          setFirstTokenFor(saved)
        }}
      />
    </>
  )

  // the first-token dialog keeps its place while the view switches from the list to the account
  return (
    <>
      {firstTokenDialog}
      {openId ? (
        <DetailView
          key={`${openId}-${detailVersion}`}
          id={openId}
          onChanged={() => void reload()}
        />
      ) : (
        list
      )}
    </>
  )
}
