import { useState } from 'react'
import Box from '@mui/material/Box'
import type { ServiceAccountDetailResponse, ServiceAccountResponse } from '@/shared/api/types'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import { definitionListSx } from '@/shared/ui/list/adminListStyles'
import IssueServiceAccountTokenDialog from './IssueServiceAccountTokenDialog'
import ServiceAccountFormDialog from './ServiceAccountFormDialog'
import { AccountState, LibraryRows, ResponsiblePerson, TokenRows } from './ServiceAccountParts'
import { formatDate, rateLimitLabel } from './serviceAccountLabels'

/**
 * The body of one service account: its facts with „Ändern“, the tokens with „Token ausstellen“
 * and the libraries that consented. A deactivated account shows everything without actions.
 */
export default function ServiceAccountDetailBody({
  detail,
  headingLevel = 'h3',
  onChanged,
}: {
  detail: ServiceAccountDetailResponse
  headingLevel?: 'h2' | 'h3'
  onChanged: (saved?: ServiceAccountResponse) => void
}) {
  const account = detail.serviceAccount
  const [editing, setEditing] = useState(false)
  const [issuing, setIssuing] = useState(false)

  return (
    <>
      <DetailSection
        title="Angaben"
        headingLevel={headingLevel}
        action={
          account.active ? (
            <TextAction onClick={() => setEditing(true)} context="Angaben">
              Ändern
            </TextAction>
          ) : undefined
        }
      >
        <Box component="dl" sx={{ ...definitionListSx, mt: 0, '& dd': { m: 0, fontSize: 13.5 } }}>
          <dt>Zustand</dt>
          <dd>
            <AccountState account={account} />
          </dd>
          <dt>Verantwortlich</dt>
          <dd>
            <ResponsiblePerson account={account} />
          </dd>
          <dt>Kontingent</dt>
          <dd>{rateLimitLabel(account)}</dd>
          {account.description && (
            <>
              <dt>Beschreibung</dt>
              <dd>{account.description}</dd>
            </>
          )}
          <dt>Angelegt</dt>
          <dd>{formatDate(account.createdAt)}</dd>
        </Box>
      </DetailSection>

      <DetailSection
        title="Tokens"
        headingLevel={headingLevel}
        action={
          account.active ? (
            <TextAction onClick={() => setIssuing(true)}>Token ausstellen</TextAction>
          ) : undefined
        }
      >
        <TokenRows account={account} tokens={detail.tokens} onChanged={() => onChanged()} />
      </DetailSection>

      <DetailSection title="Bibliotheken" headingLevel={headingLevel}>
        <LibraryRows libraries={detail.libraries} />
      </DetailSection>

      <ServiceAccountFormDialog
        open={editing}
        account={account}
        onClose={() => setEditing(false)}
        onSaved={(saved) => {
          setEditing(false)
          onChanged(saved)
        }}
      />
      <IssueServiceAccountTokenDialog
        account={issuing ? account : null}
        onClose={() => setIssuing(false)}
        onIssued={() => onChanged()}
      />
    </>
  )
}
