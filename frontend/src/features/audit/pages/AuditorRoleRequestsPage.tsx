import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import HowToRegOutlinedIcon from '@mui/icons-material/HowToRegOutlined'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import RowMenu from '@/shared/ui/RowMenu'
import AdminList, { ListText, RowAction } from '@/shared/ui/list/AdminList'
import { useAuthStore } from '@/shared/session/authStore'
import { notify } from '@/shared/stores/notificationStore'
import { useRecentLoginGate } from '@/shared/session/useRecentLoginGate'
import type { AuditorRoleRequestResponse } from '@/shared/api/types'
import { contentWidth } from '@/shared/theme/tokens'
import {
  confirmAuditorRoleRequest,
  declineAuditorRoleRequest,
  listAuditorRoleRequests,
} from '../api/auditorRoleRequestApi'
import AuditorSetupLinkDialog, { type AuditorSetupLink } from '../components/AuditorSetupLinkDialog'
import { formatDate, formatDateTime } from '@/shared/utils/formatDate'

function personOf(request: AuditorRoleRequestResponse): string {
  return request.displayName ?? request.email ?? 'Unbenanntes Konto'
}

function changedOn(iso: string): string {
  return formatDate(iso)
}

/**
 * The requests for the role Revision, asked by the system administration or by the roles claim of
 * an identity provider. Neither grants the role alone: it takes effect only once a person of the
 * revision confirms here, so that the administration never reads the audit log through an account
 * of its own. The link that sets the password of a confirmed local account is shown here once.
 */
export default function AuditorRoleRequestsPage() {
  const isAuditor = useAuthStore((s) => s.user?.systemRole === 'AUDITOR')
  const [requests, setRequests] = useState<AuditorRoleRequestResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [setupLink, setSetupLink] = useState<AuditorSetupLink | null>(null)
  const gate = useRecentLoginGate(
    'Die Rolle Revision zu vergeben, verlangt eine gerade bestätigte Anmeldung. Bitte bestätigen Sie Ihre Anmeldung.',
  )

  const [reloads, setReloads] = useState(0)

  useEffect(() => {
    if (!isAuditor) return
    let cancelled = false
    listAuditorRoleRequests()
      .then((open) => {
        if (!cancelled) setRequests(open)
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Die Anträge konnten nicht geladen werden.')
        }
      })
    return () => {
      cancelled = true
    }
  }, [isAuditor, reloads])

  async function decide(
    request: AuditorRoleRequestResponse,
    confirm: boolean,
    afterConfirmation = false,
  ): Promise<void> {
    setBusyId(request.id)
    try {
      if (confirm) {
        const url = await confirmAuditorRoleRequest(request.id)
        gate.settled()
        notify(`„${personOf(request)}“ hat jetzt die Rolle Revision.`, 'success')
        if (url) setSetupLink({ person: personOf(request), email: request.email, url })
      } else {
        await declineAuditorRoleRequest(request.id)
        notify(`Der Antrag für „${personOf(request)}“ ist abgelehnt.`, 'success')
      }
      setError(null)
      setReloads((count) => count + 1)
    } catch (err: unknown) {
      const refused = confirm
        ? gate.refusal(err, () => void decide(request, true, true), afterConfirmation)
        : null
      if (refused === null) {
        setError(err instanceof Error ? err.message : 'Der Antrag konnte nicht entschieden werden.')
      } else if (refused) {
        setError(refused)
      }
    } finally {
      setBusyId(null)
    }
  }

  if (!isAuditor) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
        <Alert severity="info">Rollenanträge entscheidet die Revision.</Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={HowToRegOutlinedIcon}
          title="Rollenanträge"
          description="Weder die Systemverwaltung noch ein Identitätsanbieter kann die Rolle Revision allein vergeben. Sie wird erst wirksam, wenn eine Person der Revision hier bestätigt. Ein lokales Konto verliert dabei sein Passwort; den Link für ein neues zeigt die Bestätigung einmalig Ihnen an, Sie übergeben ihn der Person."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {requests !== null && requests.length === 0 && (
          <Typography sx={{ color: 'text.secondary' }}>Es liegt kein Antrag vor.</Typography>
        )}

        {requests !== null && requests.length > 0 && (
          <AdminList
            label="Offene Anträge auf die Rolle Revision"
            rows={requests}
            rowKey={(request) => request.id}
            rowLabel={personOf}
            columns={[
              {
                id: 'person',
                head: 'Person',
                cell: (request) => (
                  <>
                    <ListText strong>{personOf(request)}</ListText>
                    {request.localAccount && <ListText secondary>Lokales Konto</ListText>}
                  </>
                ),
              },
              {
                id: 'email',
                head: 'Adresse',
                width: '30%',
                cell: (request) => (
                  <>
                    <ListText>{request.email ?? '—'}</ListText>
                    {request.emailChangedByAdministrationAt && (
                      <ListText secondary>
                        {`Von der Systemverwaltung geändert am ${changedOn(request.emailChangedByAdministrationAt)} – mit der Person prüfen`}
                      </ListText>
                    )}
                  </>
                ),
              },
              {
                id: 'requestedBy',
                head: 'Beantragt von',
                width: '18%',
                cell: (request) => request.requestedByDisplayName ?? 'Gelöschtes Konto',
              },
              {
                id: 'requestedAt',
                head: 'Beantragt am',
                width: 152,
                cell: (request) => formatDateTime(request.requestedAt),
              },
            ]}
            actionsWidth={176}
            rowActions={(request) => (
              <>
                <RowAction
                  label={`Antrag für ${personOf(request)} bestätigen`}
                  disabled={busyId !== null}
                  onClick={() => void decide(request, true)}
                >
                  Bestätigen
                </RowAction>
                <RowMenu
                  name={personOf(request)}
                  disabled={busyId !== null}
                  items={[{ label: 'Ablehnen', onClick: () => void decide(request, false) }]}
                />
              </>
            )}
          />
        )}
      </Box>
      <AuditorSetupLinkDialog link={setupLink} onClose={() => setSetupLink(null)} />
      {gate.dialog}
    </Box>
  )
}
