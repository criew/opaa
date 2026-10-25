import Stack from '@mui/material/Stack'
import LibraryMetadataFieldsSection from './metadata/LibraryMetadataFieldsSection'
import MetadataExtractionSettingsSection, {
  MetadataFillSection,
} from './metadata/MetadataExtractionSettingsSection'

interface LibraryMetadataTabProps {
  libraryId: string
  /** May change the schema and the extraction settings. */
  mayChange: boolean
  /** May read the extraction settings at all (MANAGER and above). */
  canEdit: boolean
}

/**
 * The „Metadaten" area: how full the fields are (the only place for it), the fields themselves and,
 * from MANAGER on, the model-based extraction.
 */
export default function LibraryMetadataTab({
  libraryId,
  mayChange,
  canEdit,
}: LibraryMetadataTabProps) {
  return (
    <Stack>
      <MetadataFillSection libraryId={libraryId} />
      <LibraryMetadataFieldsSection libraryId={libraryId} canManageSchema={mayChange} />
      {/* GET .../metadata/extraction-settings answers from MANAGER on only */}
      {canEdit && <MetadataExtractionSettingsSection libraryId={libraryId} canManage={mayChange} />}
    </Stack>
  )
}
