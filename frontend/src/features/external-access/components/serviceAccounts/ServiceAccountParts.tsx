import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import type {
  ServiceAccountConsentedLibraryResponse,
  ServiceAccountResponse,
  ServiceAccountTokenResponse,
} from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { TextLink } from '@/shared/ui/DetailSection'
import MetaBadge from '@/shared/ui/MetaBadge'
import RowMenu from '@/shared/ui/RowMenu'
import StateDot from '@/shared/ui/StateDot'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { revokeServiceAccountToken } from '../../api/serviceAccountApi'
import { TOKEN_STATUS_LABEL } from '../tokenLabels'
import {
  MISSING_PERSON,
  REVOKE_TOKEN_CONSEQUENCE,
  formatDate,
  soonLabel,
} from './serviceAccountLabels'
import { fontSize } from '@/shared/theme/tokens'

/** The responsible person, or „fehlt“ with a warning dot once it was locked or deleted. */
export function ResponsiblePerson({ account }: { account: ServiceAccountResponse }) {
  if (account.responsiblePersonMissing || !account.responsibleDisplayName) {
    return <StateDot tone="warning" label={MISSING_PERSON} />
  }
  return <span>{account.responsibleDisplayName}</span>
}

/** A consent that ran out: „Abgelaufen“ as the state, the date beside it. */
export function ExpiredOn({ value }: { value: string }) {
  return (
    <Box component="span" sx={{ display: 'inline-flex', gap: 1, flexWrap: 'wrap' }}>
      <StateDot tone="neutral" label="Abgelaufen" />
      <span>{formatDate(value)}</span>
    </Box>
  )
}

/** The account's state: „Aktiv“ or „Deaktiviert“ with a dot. */
export function AccountState({ account }: { account: ServiceAccountResponse }) {
  return account.active ? (
    <StateDot tone="success" label="Aktiv" />
  ) : (
    <StateDot tone="neutral" label="Deaktiviert" />
  )
}

/** A date with „in 3 Tagen“ beside it when it is close. */
export function Ending({ value }: { value: string | null | undefined }) {
  const soon = soonLabel(value)
  return (
    <Box component="span" sx={{ display: 'inline-flex', gap: 1, flexWrap: 'wrap' }}>
      <span>{formatDate(value)}</span>
      {soon && <MetaBadge>{soon}</MetaBadge>}
    </Box>
  )
}

const TOKEN_TONE: Record<ServiceAccountTokenResponse['status'], StatusTone> = {
  ACTIVE: 'success',
  EXPIRED: 'neutral',
  REVOKED: 'neutral',
  BLOCKED: 'error',
}

const rowSx = {
  display: 'grid',
  gridTemplateColumns: 'minmax(0, 1fr) auto',
  columnGap: 1.5,
  alignItems: 'center',
  py: 1.25,
  borderBottom: 1,
  borderColor: 'divider',
} as const

/** One row per token: name, then prefix and end; state; „⋯“ with „Widerrufen“. */
export function TokenRows({
  account,
  tokens,
  onChanged,
}: {
  account: ServiceAccountResponse
  tokens: ServiceAccountTokenResponse[]
  onChanged: () => void
}) {
  async function revoke(token: ServiceAccountTokenResponse) {
    const confirmed = await confirmAction({
      question: `„${token.name}“ widerrufen?`,
      consequence: REVOKE_TOKEN_CONSEQUENCE,
      confirmLabel: 'Widerrufen',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await revokeServiceAccountToken(account.id, token.id)
      notify(`Das Token „${token.name}“ ist widerrufen.`, 'success')
      onChanged()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Das Widerrufen ist fehlgeschlagen.', 'error')
    }
  }

  if (tokens.length === 0) {
    return (
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', py: 1 }}>
        Noch kein Token.
      </Typography>
    )
  }
  return (
    <Box component="ul" aria-label="Tokens" sx={{ listStyle: 'none', p: 0, m: 0 }}>
      {tokens.map((token) => (
        <Box component="li" key={token.id} sx={rowSx}>
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: fontSize.text, fontWeight: 600, overflowWrap: 'anywhere' }}>
              {token.name}
            </Typography>
            <Typography
              component="div"
              sx={{
                fontSize: fontSize.meta,
                color: 'text.secondary',
                display: 'flex',
                gap: 1.5,
                flexWrap: 'wrap',
              }}
            >
              <StateDot tone={TOKEN_TONE[token.status]} label={TOKEN_STATUS_LABEL[token.status]} />
              <span>
                {token.status === 'ACTIVE' ? 'bis ' : ''}
                {token.status === 'ACTIVE' ? (
                  <Ending value={token.expiresAt} />
                ) : (
                  formatDate(token.expiresAt)
                )}
              </span>
            </Typography>
          </Box>
          {token.status === 'ACTIVE' && account.active ? (
            <RowMenu
              name={token.name}
              items={[{ label: 'Widerrufen', onClick: () => void revoke(token), danger: true }]}
            />
          ) : (
            <span />
          )}
        </Box>
      ))}
    </Box>
  )
}

/** One row per consented library: link into its „Freigaben“, the end, an expired state. */
export function LibraryRows({
  libraries,
}: {
  libraries: ServiceAccountConsentedLibraryResponse[]
}) {
  if (libraries.length === 0) {
    return (
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', py: 1 }}>
        Noch keine Bibliothek. Die Verwaltung einer Bibliothek nimmt das Dienstkonto dort unter
        „Freigaben“ auf.
      </Typography>
    )
  }
  return (
    <Box component="ul" aria-label="Bibliotheken" sx={{ listStyle: 'none', p: 0, m: 0 }}>
      {libraries.map((library) => (
        <Box component="li" key={library.libraryId} sx={{ ...rowSx, gridTemplateColumns: '1fr' }}>
          <Box sx={{ minWidth: 0 }}>
            <TextLink
              to={`/libraries/${library.libraryId}?tab=freigaben`}
              sx={{ overflowWrap: 'anywhere', fontWeight: 600 }}
            >
              {library.libraryName}
            </TextLink>
            <Typography component="div" sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
              {library.status === 'ACTIVE' ? (
                <>
                  bis <Ending value={library.expiresAt} />
                </>
              ) : (
                <ExpiredOn value={library.expiresAt} />
              )}
            </Typography>
          </Box>
        </Box>
      ))}
    </Box>
  )
}

/** Loading and error frame of a detail view. */
export function DetailFrame({
  error,
  loading,
  onRetry,
  children,
}: {
  error: string | null
  loading: boolean
  onRetry: () => void
  children: ReactNode
}) {
  if (error) {
    return (
      <Alert
        severity="error"
        action={
          <Button color="inherit" size="small" onClick={onRetry}>
            Erneut laden
          </Button>
        }
      >
        {error}
      </Alert>
    )
  }
  if (loading) return <Skeleton variant="rounded" height={200} />
  return <>{children}</>
}
