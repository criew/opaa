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
 * whose connector refuses it with kind and reason, and that saving may discard secrets and sync
 * state - whether it does, the server decides and confirms with its own question.
 */
export default function ProfileChangePreview({ impact, ownership }: ProfileChangePreviewProps) {
  const unnamed = impact.rejectedLibraries - impact.rejections.length
  return (
    <Stack spacing={1.5} data-testid="profile-change-preview">
      <Typography variant="subtitle2" component="h3">
        Auswirkungen auf die Bibliotheken
      </Typography>
      <Typography variant="body2">
        Betroffen: {count(impact.libraries, 'Bibliothek', 'Bibliotheken')}.
        {admitsPersons(ownership) &&
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
        <Alert severity="success">Der Konnektor nimmt die Änderung für alle Bibliotheken an.</Alert>
      )}
      {(impact.fullSyncLibraries ?? 0) > 0 && (
        <Alert severity="info" data-testid="profile-change-full-sync">
          Eine Vorgabe, die nur der Zugang setzt, ändert sich: Der Abgleichstand von{' '}
          {count(impact.fullSyncLibraries ?? 0, 'Bibliothek', 'Bibliotheken')} wird verworfen, ihr
          nächster Lauf liest die Quelle vollständig neu. Ihre Verwaltenden werden benachrichtigt.
        </Alert>
      )}
      <Alert severity="warning">
        Zugangsdaten und Abgleichstand der verbundenen Bibliotheken können beim Speichern verworfen
        werden. Verwirft die Änderung Zugangsdaten, fragt OPAA vor dem Speichern noch einmal nach.
      </Alert>
    </Stack>
  )
}
