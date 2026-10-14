import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { DirectorySyncStatusResponse } from '@/shared/api/types'
import { getDirectorySyncStatus } from '../api/directorySyncApi'
import { ageSinceLabel, countLabel } from '@/features/principal'
import HintLink from '@/shared/ui/list/HintLink'

/**
 * A pending directory plan is a loud state (ADR-0036, Entscheidung 3): its age
 * is shown on the administration overview, not only on the provider's sub-page. Here as a
 * warning link in the row of „Gruppe anlegen", with the age already in the link text and the
 * details per provider in the popover.
 */
export default function PendingPlanHint() {
  const [statuses, setStatuses] = useState<DirectorySyncStatusResponse[]>([])

  useEffect(() => {
    void getDirectorySyncStatus()
      .then(setStatuses)
      .catch(() => setStatuses([]))
  }, [])

  const waiting = statuses.filter((status) => status.pendingPlan)
  if (waiting.length === 0) return null

  const text =
    waiting.length === 1
      ? `Verzeichnisplan wartet seit ${ageSinceLabel(waiting[0].pendingPlan!.createdAt)} auf Entscheidung`
      : `${waiting.length} Verzeichnispläne warten auf Entscheidung`

  return (
    <HintLink text={text} title="Verzeichnisabgleich wartet auf Ihre Entscheidung" tone="warning">
      {(close) => (
        <>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
            Ein Abgleich würde mehr Mitgliedschaften entziehen oder mehr Konten sperren, als OPAA
            ohne Rückfrage anwendet. Bis Sie den Plan bestätigen oder verwerfen, bleibt der
            bisherige Stand in Kraft.
          </Typography>
          <Stack spacing={1} sx={{ mb: 1.5 }}>
            {waiting.map((status) => {
              const plan = status.pendingPlan!
              const removed = plan.membershipsRemoved
              return (
                <Box key={status.providerId}>
                  <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
                    {status.providerDisplayName}
                  </Typography>
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    wartet seit {ageSinceLabel(plan.createdAt)} ·{' '}
                    {countLabel(removed, 'Mitgliedschaft', 'Mitgliedschaften')}{' '}
                    {removed === 1 ? 'würde' : 'würden'} entzogen ·{' '}
                    {countLabel(plan.accountsLocked ?? 0, 'Konto', 'Konten')} gesperrt
                  </Typography>
                </Box>
              )
            })}
          </Stack>
          <Button
            component={RouterLink}
            to="/admin/directory-sync"
            size="small"
            variant="outlined"
            onClick={close}
          >
            Zum Verzeichnisabgleich
          </Button>
        </>
      )}
    </HintLink>
  )
}
