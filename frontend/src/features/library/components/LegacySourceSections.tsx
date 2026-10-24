import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import PageSection from '@/shared/ui/PageSection'
import { scheduleFrequencyLabel } from './scheduleLabels'
import { sourceRegistration } from './sources/registry'
import type { StoredLibrarySource } from './sources/types'

interface LegacySourceSectionsProps {
  library: StoredLibrarySource
  libraryId: string
  canEditSource: boolean
  erasing: boolean
  onEdit: () => void
  onEditSchedule: () => void
}

/** The Reiter „Quelle“ of a source type not yet on the shared frame (Webverzeichnis, RSS-Feed). */
export default function LegacySourceSections({
  library,
  libraryId,
  canEditSource,
  erasing,
  onEdit,
  onEditSchedule,
}: LegacySourceSectionsProps) {
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const Scope = configuration?.Scope
  const StoredView = configuration?.StoredView
  return (
    <>
      {Scope && (
        <PageSection title="Umfang">
          <Scope library={library} />
        </PageSection>
      )}
      {canEditSource && !erasing && (
        <>
          <PageSection
            title="Verbindung"
            action={
              configuration ? (
                <Button size="small" variant="outlined" onClick={onEdit}>
                  Quelle bearbeiten
                </Button>
              ) : undefined
            }
          >
            {StoredView ? (
              <StoredView library={library} libraryId={libraryId} />
            ) : (
              <Typography variant="body2" data-testid="source-not-configurable">
                Für diese Quellart gibt es hier keine Eingabemaske.
              </Typography>
            )}
          </PageSection>
          <PageSection
            title="Zeitplan"
            action={
              <Button
                size="small"
                variant="outlined"
                onClick={onEditSchedule}
                aria-label="Zeitplan bearbeiten"
              >
                Bearbeiten
              </Button>
            }
          >
            <Typography variant="body2">
              {scheduleFrequencyLabel(library.schedule?.frequency ?? 'DISABLED')}
            </Typography>
          </PageSection>
        </>
      )}
    </>
  )
}
