import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import type { ConnectionProfileImpactResponse } from '../../../types/api'
import { rejectionCategoryLabel, type ProfileChange } from './profileChange'

function count(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`
}

interface ProfileChangePreviewProps {
  impact: ConnectionProfileImpactResponse
  change: ProfileChange
}

/**
 * The preview of an edit of a profile before it is saved: how many libraries it reaches, every one
 * whose connector refuses it with kind and reason, and what saving discards.
 */
export default function ProfileChangePreview({ impact, change }: ProfileChangePreviewProps) {
  const unnamed = impact.rejectedLibraries - impact.rejections.length
  return (
    <Stack spacing={1.5} data-testid="profile-change-preview">
      <Typography variant="subtitle2" component="h3">
        Auswirkungen auf die Bibliotheken
      </Typography>
      <Typography variant="body2">
        Betroffen: {count(impact.libraries, 'Bibliothek', 'Bibliotheken')} mit{' '}
        {count(impact.connections, 'Verbindung', 'Verbindungen')}.
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
      {(change.discardsSecrets || change.changesConfiguration) && (
        <Alert severity="warning">
          {change.discardsSecrets &&
            'Beim Speichern werden die hinterlegten Zugangsdaten aller Verbindungen verworfen und müssen neu eingetragen werden. '}
          {change.changesConfiguration &&
            'Bei jeder Bibliothek, deren Konfiguration sich ändert, verwirft der Konnektor den Abgleichstand, den die Änderung ungültig macht; der nächste Lauf gleicht dann neu ab.'}
        </Alert>
      )}
    </Stack>
  )
}
