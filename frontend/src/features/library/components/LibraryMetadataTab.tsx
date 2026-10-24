import Stack from '@mui/material/Stack'
import PageSection from '@/shared/ui/PageSection'
import LibraryMetadataFieldsSection from './metadata/LibraryMetadataFieldsSection'
import MetadataExtractionSettingsSection from './metadata/MetadataExtractionSettingsSection'

interface LibraryMetadataTabProps {
  libraryId: string
  /** May change the schema and the extraction settings. */
  mayChange: boolean
  /** May read the extraction settings at all (MANAGER and above). */
  canEdit: boolean
}

/** The „Metadaten" area: the library's own fields and the model-based extraction. */
export default function LibraryMetadataTab({
  libraryId,
  mayChange,
  canEdit,
}: LibraryMetadataTabProps) {
  return (
    <Stack>
      <PageSection
        title="Metadatenfelder"
        description="Eigene typisierte Felder dieser Bibliothek, ihre Wirkstellen und ihre Wertelisten."
      >
        <LibraryMetadataFieldsSection libraryId={libraryId} canManageSchema={mayChange} />
      </PageSection>
      {/* The extraction settings are readable only from MANAGER on
               (GET .../metadata/extraction-settings) - for a reading role only an error message
               would remain here. */}
      {canEdit && (
        <PageSection
          title="Modellgestützte Extraktion"
          description="Ob das Sprachmodell leer gebliebene Felder ergänzt und freie Schlagworte vergibt — und wie gut die Extraktion diese Bibliothek beschreibt."
        >
          <MetadataExtractionSettingsSection libraryId={libraryId} canManage={mayChange} />
        </PageSection>
      )}
    </Stack>
  )
}
