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
  /** The tab is shown; the fill counts are fetched anew on every visit. */
  active: boolean
  onFieldsChanged?: () => void
}

/**
 * The „Metadaten" area: how full the fields are (the only place for it), the fields themselves and,
 * from MANAGER on, the model-based extraction.
 */
export default function LibraryMetadataTab({
  libraryId,
  mayChange,
  canEdit,
  active,
  onFieldsChanged,
}: LibraryMetadataTabProps) {
  return (
    <Stack>
      <MetadataFillSection libraryId={libraryId} active={active} />
      <LibraryMetadataFieldsSection
        libraryId={libraryId}
        canManageSchema={mayChange}
        onFieldsChanged={onFieldsChanged}
        // GET .../metadata/extraction-settings answers from MANAGER on only
        trailingRows={
          canEdit && (
            <MetadataExtractionSettingsSection libraryId={libraryId} canManage={mayChange} />
          )
        }
      />
    </Stack>
  )
}
