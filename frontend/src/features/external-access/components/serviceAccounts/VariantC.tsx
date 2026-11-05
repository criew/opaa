import { useEffect, useId, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { ServiceAccountResponse } from '@/shared/api/types'
import PageSection from '@/shared/ui/PageSection'
import RowMenu from '@/shared/ui/RowMenu'
import { ListEmptyState, ListLoading } from '@/shared/ui/list/AdminList'
import { definitionListSx } from '@/shared/ui/list/adminListStyles'
import IssueServiceAccountTokenDialog from './IssueServiceAccountTokenDialog'
import ServiceAccountDetailBody from './ServiceAccountDetailBody'
import ServiceAccountFormDialog from './ServiceAccountFormDialog'
import { AccountState, DetailFrame, Ending, ResponsiblePerson } from './ServiceAccountParts'
import { confirmDeactivate, useServiceAccountDetail } from './serviceAccountHooks'
import { rateLimitLabel, tokenCountLabel } from './serviceAccountLabels'
import { useServiceAccounts } from './useServiceAccounts'

function Expanded({
  id,
  version,
  onChanged,
}: {
  id: string
  /** Raised by every change from the row's menu, so the opened part follows. */
  version: number
  onChanged: () => void
}) {
  const { detail, error, reload } = useServiceAccountDetail(id)
  useEffect(() => {
    if (version > 0) void reload()
  }, [version, reload])
  return (
    <Box sx={{ pl: { xs: 0, sm: 4 }, pb: 2 }}>
      <DetailFrame error={error} loading={!detail} onRetry={() => void reload()}>
        {detail && (
          <>
            <Box component="dl" sx={{ ...definitionListSx, mt: 0, mb: 2 }}>
              <dt>Kontingent</dt>
              <dd>{rateLimitLabel(detail.serviceAccount)}</dd>
              {detail.serviceAccount.description && (
                <>
                  <dt>Beschreibung</dt>
                  <dd>{detail.serviceAccount.description}</dd>
                </>
              )}
            </Box>
            <ServiceAccountDetailBody
              detail={detail}
              showFacts={false}
              onChanged={() => {
                void reload()
                onChanged()
              }}
            />
          </>
        )}
      </DetailFrame>
    </Box>
  )
}

function AccountRow({
  account,
  expanded,
  version,
  onToggle,
  onEdit,
  onIssue,
  onChanged,
}: {
  account: ServiceAccountResponse
  expanded: boolean
  version: number
  onToggle: () => void
  onEdit: () => void
  onIssue: () => void
  onChanged: () => void
}) {
  const regionId = useId()
  return (
    <Box component="li" sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <ButtonBase
          onClick={onToggle}
          aria-expanded={expanded}
          aria-controls={regionId}
          sx={{
            flex: 1,
            minWidth: 0,
            justifyContent: 'flex-start',
            textAlign: 'left',
            gap: 1.5,
            py: 1.25,
            borderRadius: 1,
            '&:hover': { bgcolor: 'action.hover' },
          }}
        >
          <ExpandMoreIcon
            fontSize="small"
            aria-hidden="true"
            sx={{
              color: 'text.secondary',
              transform: expanded ? 'rotate(180deg)' : 'none',
              transition: 'transform 120ms',
              '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
            }}
          />
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
              {account.name}
            </Typography>
            <Typography
              component="div"
              sx={{
                fontSize: 12.5,
                color: 'text.secondary',
                display: 'flex',
                gap: 1,
                flexWrap: 'wrap',
              }}
            >
              {account.active ? (
                <>
                  <ResponsiblePerson account={account} />
                  <span aria-hidden="true">·</span>
                  <span>{tokenCountLabel(account.activeTokenCount)}</span>
                  {account.nextExpiryAt && (
                    <>
                      <span aria-hidden="true">·</span>
                      <span>
                        Ablauf <Ending value={account.nextExpiryAt} />
                      </span>
                    </>
                  )}
                </>
              ) : (
                <AccountState account={account} />
              )}
            </Typography>
          </Box>
        </ButtonBase>
        {account.active && (
          <RowMenu
            name={account.name}
            items={[
              { label: 'Angaben ändern', onClick: onEdit },
              { label: 'Token ausstellen', onClick: onIssue },
              {
                label: 'Deaktivieren',
                danger: true,
                onClick: () => void confirmDeactivate(account, onChanged),
              },
            ]}
          />
        )}
      </Box>
      <Box id={regionId} role="region" aria-label={account.name} hidden={!expanded}>
        {expanded && <Expanded id={account.id} version={version} onChanged={onChanged} />}
      </Box>
    </Box>
  )
}

/**
 * Variant C: the service accounts as rows that open in place, without leaving the tab; a new
 * account leads straight on to its first token.
 */
export default function ServiceAccountsSectionC() {
  const { accounts, error, reload } = useServiceAccounts()
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<ServiceAccountResponse | null>(null)
  const [issuingFor, setIssuingFor] = useState<{
    account: ServiceAccountResponse
    first: boolean
  } | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [version, setVersion] = useState(0)

  function changed() {
    void reload()
    setVersion((value) => value + 1)
  }

  return (
    <PageSection
      title={
        accounts && !error
          ? `Dienstkonten (${accounts.filter((a) => a.active).length})`
          : 'Dienstkonten'
      }
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
        <Box
          component="ul"
          aria-label="Dienstkonten"
          sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderColor: 'divider' }}
        >
          {accounts.map((account) => (
            <AccountRow
              key={account.id}
              version={version}
              account={account}
              expanded={expanded === account.id}
              onToggle={() => setExpanded((open) => (open === account.id ? null : account.id))}
              onEdit={() => setEditing(account)}
              onIssue={() => setIssuingFor({ account, first: false })}
              onChanged={changed}
            />
          ))}
        </Box>
      )}

      <ServiceAccountFormDialog
        open={creating || editing !== null}
        account={editing}
        createLabel="Weiter"
        onClose={() => {
          setCreating(false)
          setEditing(null)
        }}
        onSaved={(saved) => {
          const wasNew = creating
          setCreating(false)
          setEditing(null)
          changed()
          if (wasNew) {
            setExpanded(saved.id)
            setIssuingFor({ account: saved, first: true })
          }
        }}
      />
      <IssueServiceAccountTokenDialog
        account={issuingFor?.account ?? null}
        title={issuingFor?.first ? 'Erstes Token ausstellen' : 'Token ausstellen'}
        cancelLabel={issuingFor?.first ? 'Ohne Token beenden' : 'Abbrechen'}
        onClose={() => setIssuingFor(null)}
        onIssued={changed}
      />
    </PageSection>
  )
}
