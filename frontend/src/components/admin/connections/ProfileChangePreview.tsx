import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import type { ConnectionOwnership, ConnectionProfileImpactResponse } from '../../../types/api'
import { admitsPersons, personCountLabel, rejectionCategoryLabel } from './profileChange'

function count(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`
}

interface ProfileChangePreviewProps {
  impact: ConnectionProfileImpactResponse
  /** The ownership of the profile - connected accounts of persons are named only where admitted. */
  ownership: ConnectionOwnership
}

/**
 * The preview of an edit of a profile before it is saved: how many libraries it reaches, every one
 * whose connector refuses it with kind and reason, and what saving discards as the server plans it
 * - with the question it asks before, word for word.
 */
export default function ProfileChangePreview({ impact, ownership }: ProfileChangePreviewProps) {
  const unnamed = impact.rejectedLibraries - impact.rejections.length
  // Private libraries may exist only where persons are admitted; their refusals are a masked count.
  const persons = admitsPersons(ownership)
  return (
    <Stack spacing={1.5} data-testid="profile-change-preview">
      <Typography variant="subtitle2" component="h3">
        Auswirkungen auf die Bibliotheken
      </Typography>
      <Typography variant="body2">
        Betroffen: {count(impact.libraries, 'Bibliothek', 'Bibliotheken')}.
        {persons &&
          ` Verbundene Konten von Personen: ${personCountLabel(impact.connectedAccounts)}.`}
      </Typography>
      {impact.rejectedLibraries > 0 ? (
        <Alert severity="error" data-testid="profile-change-rejections">
          <Typography variant="body2" sx={{ mb: 1 }}>
            Der Konnektor lehnt die Änderung für{' '}
            {count(impact.rejectedLibraries, 'Bibliothek', 'Bibliotheken')} ab. So lässt sie sich
            nicht speichern; es bliebe alles, wie es ist.
          </Typography>
          <List dense disablePadding>
            {impact.rejections.map((rejection, index) => (
              <ListItem key={`${rejection.libraryId}-${index}`} disableGutters sx={{ py: 0.25 }}>
                <Typography variant="body2">
                  <strong>{rejectionCategoryLabel(rejection.category)}:</strong> {rejection.message}{' '}
                  (
                  <Link
                    component={RouterLink}
                    to={`/libraries/${rejection.libraryId}`}
                    aria-label={`Abgelehnte Bibliothek ${index + 1} öffnen`}
                  >
                    Bibliothek öffnen
                  </Link>
                  )
                </Typography>
              </ListItem>
            ))}
          </List>
          {unnamed > 0 && (
            <Typography variant="body2" sx={{ mt: 1 }}>
              {count(unnamed, 'weitere Bibliothek', 'weitere Bibliotheken')} ohne Angabe.
            </Typography>
          )}
        </Alert>
      ) : (
        <Alert severity="success">
          {persons
            ? 'Der Konnektor nimmt die Änderung für alle geteilten Bibliotheken an.'
            : 'Der Konnektor nimmt die Änderung für alle Bibliotheken an.'}
        </Alert>
      )}
      {persons && (
        <Typography variant="body2" data-testid="profile-change-private-rejections">
          Private Bibliotheken, für die der Konnektor ablehnt:{' '}
          {personCountLabel(impact.rejectedPrivateLibraries)}. Sie verhindern die Änderung nicht;
          eine abgelehnte private Bibliothek wird vom Zugang gelöst und ruht, bis ihre Besitzerin
          sie einem anderen Zugang zuordnet.
        </Typography>
      )}
      {(impact.fullSyncLibraries ?? 0) > 0 && (
        <Alert severity="info" data-testid="profile-change-full-sync">
          Eine Vorgabe, die nur der Zugang setzt, ändert sich: Der Abgleichstand von{' '}
          {count(impact.fullSyncLibraries ?? 0, 'Bibliothek', 'Bibliotheken')} wird verworfen, ihr
          nächster Lauf liest die Quelle vollständig neu. Ihre Verwaltenden werden benachrichtigt.
        </Alert>
      )}
      <ProfileChangeDiscards impact={impact} persons={persons} />
    </Stack>
  )
}

/** What saving discards, each number as the server counts it; persons only as it rounds them. */
function ProfileChangeDiscards({
  impact,
  persons,
}: {
  impact: ConnectionProfileImpactResponse
  persons: boolean
}) {
  const connections = impact.connectionsDiscarded ?? 0
  const secrets = impact.secretsDiscarded ?? 0
  const configurations = impact.configurationsChanged ?? 0
  const accounts = persons ? (impact.connectedAccountsEnded ?? null) : null
  const items: string[] = []
  if (connections > 0) {
    items.push(
      `Alle Zugangsdaten und Token des Zugangs werden verworfen; ${count(connections, 'Verbindung meldet', 'Verbindungen melden')} sich neu an.`,
    )
  }
  if (secrets > 0) {
    items.push(
      `Die gespeicherten Zugangsdaten von ${count(secrets, 'Bibliothek', 'Bibliotheken')} werden verworfen und sind neu einzutragen.`,
    )
  }
  if (accounts) {
    items.push(
      `Die verbundenen Konten von Personen enden (${personCountLabel(accounts)}); wer weiter darüber arbeitet, verbindet sein Konto neu.`,
    )
  }
  if (configurations > 0) {
    items.push(
      `Für ${count(configurations, 'Bibliothek', 'Bibliotheken')} ändert sich die Konfiguration; ihr Konnektor verwirft den Abgleichstand, den die alte Konfiguration ungültig macht.`,
    )
  }
  return (
    <Alert
      severity={impact.confirmation ? 'warning' : 'info'}
      data-testid="profile-change-discards"
    >
      <Typography variant="body2" sx={{ mb: items.length > 0 ? 1 : 0 }}>
        {items.length > 0
          ? 'Beim Speichern:'
          : 'Die Änderung verwirft keine Zugangsdaten, kein verbundenes Konto und keinen Abgleichstand.'}
      </Typography>
      {items.length > 0 && (
        <List dense disablePadding>
          {items.map((item) => (
            <ListItem key={item} disableGutters sx={{ py: 0.25 }}>
              <Typography variant="body2">{item}</Typography>
            </ListItem>
          ))}
        </List>
      )}
      {impact.confirmation && (
        <Typography variant="body2" sx={{ mt: 1 }} data-testid="profile-change-confirmation">
          Vor dem Speichern fragt OPAA noch einmal nach: „{impact.confirmation}“
        </Typography>
      )}
    </Alert>
  )
}
