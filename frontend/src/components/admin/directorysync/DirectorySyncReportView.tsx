import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  DirectorySyncGroupChange,
  DirectorySyncReportResponse,
  DirectorySyncUserRef,
} from '../../../types/api'
import StatusLine from '../../StatusLine'
import { formatFraction, outcomeLabel, outcomeTone } from './directorySyncLabels'

function groupLine(change: DirectorySyncGroupChange): string {
  const path = change.sourcePath ? ` · ${change.sourcePath}` : ''
  const previous = change.previousName ? ` (vorher „${change.previousName}")` : ''
  const members = change.memberCount === 1 ? '1 Mitglied' : `${change.memberCount} Mitglieder`
  return `${change.name}${previous}${path} — ${members}`
}

function GroupList({ title, changes }: { title: string; changes: DirectorySyncGroupChange[] }) {
  if (changes.length === 0) return null
  return (
    <Box>
      <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
        {title} ({changes.length})
      </Typography>
      <Stack component="ul" sx={{ m: 0, pl: 2.5 }}>
        {changes.map((change) => (
          <Typography component="li" key={change.externalId} sx={{ fontSize: 13 }}>
            {groupLine(change)}
          </Typography>
        ))}
      </Stack>
    </Box>
  )
}

function userNames(users: DirectorySyncUserRef[]): string {
  return users.map((user) => user.displayName ?? user.userId).join(', ')
}

function UserList({ title, users }: { title: string; users: DirectorySyncUserRef[] }) {
  if (users.length === 0) return null
  return (
    <Typography sx={{ fontSize: 13 }}>
      <strong>
        {title} ({users.length}):
      </strong>{' '}
      {userNames(users)}
    </Typography>
  )
}

/**
 * Der Differenzbericht eines Laufs (#237, #1816): was entstünde, was wegfiele, wer den Zugang
 * verlöre — mit der Mitgliederzahl je Gruppe, damit der Betrieb sie vor dem Anwenden sieht.
 */
export default function DirectorySyncReportView({
  report,
}: {
  report: DirectorySyncReportResponse
}) {
  return (
    <Box sx={{ mt: 1.5 }}>
      <StatusLine
        headline={outcomeLabel(report.outcome)}
        tone={outcomeTone(report.outcome)}
        detail={report.message}
        label="Ergebnis des Laufs"
      />
      <Stack spacing={1} sx={{ mt: 1 }}>
        <Typography sx={{ fontSize: 13 }}>
          Mitgliedschaften: +{report.membershipsAdded} / −{report.membershipsRemoved} · Anteil{' '}
          {formatFraction(report.changedFraction)} bei einer Schwelle von{' '}
          {formatFraction(report.thresholdFraction)}
        </Typography>
        <GroupList title="Neue Gruppen" changes={report.groupsCreated} />
        <GroupList title="Umbenannte Gruppen" changes={report.groupsRenamed} />
        <GroupList title="Aufgelöste Gruppen" changes={report.groupsDissolved} />
        {report.unmaintainedTokenGroups.length > 0 && (
          <Box>
            <GroupList
              title="Werden nicht mehr gepflegt (Token-Gruppen)"
              changes={report.unmaintainedTokenGroups}
            />
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              Ihre Mitgliedschaft bleibt eingefroren stehen, und sie sind kein neues Ziel für
              Berechtigungen mehr. Nichts wird stillschweigend entzogen — die Wirkungen dieser
              Gruppen lassen sich in der Arbeitsliste des Anbieters übertragen.
            </Typography>
          </Box>
        )}
        {report.membershipChanges.length > 0 && (
          <Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
              Mitgliedschaften je Gruppe ({report.membershipChanges.length})
            </Typography>
            <Stack component="ul" sx={{ m: 0, pl: 2.5 }}>
              {report.membershipChanges.map((change) => (
                <Typography component="li" key={change.externalId} sx={{ fontSize: 13 }}>
                  {change.name}:{' '}
                  {change.added.length > 0 && `aufgenommen ${userNames(change.added)}`}
                  {change.added.length > 0 && change.removed.length > 0 && ' · '}
                  {change.removed.length > 0 && `entfernt ${userNames(change.removed)}`}
                </Typography>
              ))}
            </Stack>
          </Box>
        )}
        <UserList title="Konten, die gesperrt würden" users={report.accountsLocked} />
        <UserList title="Konten, die entsperrt würden" users={report.accountsUnlocked} />
        {report.accountLocksWithheld.length > 0 && (
          <Typography sx={{ fontSize: 13 }}>
            <strong>Nicht gesperrt ({report.accountLocksWithheld.length}):</strong>{' '}
            {userNames(report.accountLocksWithheld)} — eine Sperre ließe die Installation ohne
            anmeldefähigen Systemverwalter zurück.
          </Typography>
        )}
      </Stack>
    </Box>
  )
}
