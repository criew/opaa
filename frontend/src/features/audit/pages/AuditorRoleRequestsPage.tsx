import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import HowToRegOutlinedIcon from '@mui/icons-material/HowToRegOutlined'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import { useAuthStore } from '@/shared/session/authStore'
import { notify } from '@/shared/stores/notificationStore'
import type { AuditorRoleRequestResponse } from '@/shared/api/types'
import { contentWidth } from '@/shared/theme/tokens'
import {
  confirmAuditorRoleRequest,
  declineAuditorRoleRequest,
  listAuditorRoleRequests,
} from '../api/auditorRoleRequestApi'
import AuditorSetupLinkDialog, { type AuditorSetupLink } from '../components/AuditorSetupLinkDialog'

function personOf(request: AuditorRoleRequestResponse): string {
  return request.displayName ?? request.email ?? 'Unbenanntes Konto'
}

function changedOn(iso: string): string {
  return new Date(iso).toLocaleDateString('de-DE')
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

  async function decide(request: AuditorRoleRequestResponse, confirm: boolean): Promise<void> {
    setBusyId(request.id)
    try {
      if (confirm) {
        const url = await confirmAuditorRoleRequest(request.id)
        notify(`„${personOf(request)}“ hat jetzt die Rolle Revision.`, 'success')
        if (url) setSetupLink({ person: personOf(request), email: request.email, url })
      } else {
        await declineAuditorRoleRequest(request.id)
        notify(`Der Antrag für „${personOf(request)}“ ist abgelehnt.`, 'success')
      }
      setError(null)
      setReloads((count) => count + 1)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Der Antrag konnte nicht entschieden werden.')
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
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
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
          <Table size="small" aria-label="Offene Anträge auf die Rolle Revision">
            <TableHead>
              <TableRow>
                <TableCell>Person</TableCell>
                <TableCell>Adresse</TableCell>
                <TableCell>Beantragt von</TableCell>
                <TableCell>Beantragt am</TableCell>
                <TableCell>Entscheidung</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {requests.map((request) => (
                <TableRow key={request.id}>
                  <TableCell>
                    {personOf(request)}
                    {request.localAccount && (
                      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                        Lokales Konto
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    {request.email ?? '—'}
                    {request.emailChangedByAdministrationAt && (
                      <Typography sx={{ fontSize: 12, fontWeight: 600 }}>
                        {`Von der Systemverwaltung geändert am ${changedOn(request.emailChangedByAdministrationAt)} – mit der Person prüfen`}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>{request.requestedByDisplayName ?? 'Gelöschtes Konto'}</TableCell>
                  <TableCell>{new Date(request.requestedAt).toLocaleString('de-DE')}</TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1}>
                      <Button
                        size="small"
                        variant="contained"
                        disabled={busyId !== null}
                        onClick={() => void decide(request, true)}
                        aria-label={`Antrag für ${personOf(request)} bestätigen`}
                      >
                        Bestätigen
                      </Button>
                      <Button
                        size="small"
                        disabled={busyId !== null}
                        onClick={() => void decide(request, false)}
                        aria-label={`Antrag für ${personOf(request)} ablehnen`}
                      >
                        Ablehnen
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Box>
      <AuditorSetupLinkDialog link={setupLink} onClose={() => setSetupLink(null)} />
    </Box>
  )
}
