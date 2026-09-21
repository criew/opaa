import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import { Link as RouterLink } from 'react-router'
import type { DirectorySyncStatusResponse } from '../../../types/api'
import { getDirectorySyncStatus } from '../../../services/directorySyncApi'
import { ageLabel } from '../../groups/groupOriginLabels'

/**
 * Ein ausstehender Plan ist ein lauter Zustand (ADR-0036, Entscheidung 3): Sein Alter steht auf
 * der Verwaltungsübersicht, nicht nur in der Unterseite des Anbieters.
 */
export default function PendingPlanNotice() {
  const [statuses, setStatuses] = useState<DirectorySyncStatusResponse[]>([])

  useEffect(() => {
    void getDirectorySyncStatus()
      .then(setStatuses)
      .catch(() => setStatuses([]))
  }, [])

  const waiting = statuses.filter((status) => status.pendingPlan)
  if (waiting.length === 0) return null

  return (
    <Alert severity="warning" sx={{ mb: 2 }}>
      {waiting.map((status) => (
        <div key={status.providerId}>
          {status.providerDisplayName}: Ein Verzeichnisplan wartet seit{' '}
          {ageLabel(status.pendingPlan!.createdAt)} auf eine Entscheidung —{' '}
          {status.pendingPlan!.membershipsRemoved} Mitgliedschaften würden entzogen,{' '}
          {status.pendingPlan!.accountsLocked ?? 0} Konten gesperrt.
        </div>
      ))}
      <Link component={RouterLink} to="/admin/directory-sync">
        Zum Verzeichnisabgleich
      </Link>
    </Alert>
  )
}
