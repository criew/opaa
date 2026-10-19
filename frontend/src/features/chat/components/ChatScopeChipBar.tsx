import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import type { MetadataFilter, MetadataFilterOptionsResponse } from '@/shared/api/types'
import { CHAT_MAX_WIDTH } from '@/shared/theme/theme'
import type { SearchScope } from '../stores/chatSettingsPersistence'
import type { MetadataFilterScope } from '../stores/metadataFilterOptionsStore'
import MetadataFilterPopover from './MetadataFilterPopover'
import type { SelectedChatAction } from './useChatActions'
import type { LibraryChip } from './useSpaceLibraryScope'
import {
  formatFieldChipLabel,
  formatFieldLabel,
  libraryFieldChipLabel,
  libraryFieldLabel,
  withoutFormatField,
  withoutLibraryField,
} from './metadataFilterText'

interface ChatScopeChipBarProps {
  /** The action mark the next question carries, e.g. a prompt; null without one. */
  selected: SelectedChatAction | null
  onClearSelected: () => void
  /** Locks the whole input. */
  disabled: boolean
  scope: SearchScope
  /** Scope and filter stay as they are while an answer is pending. */
  scopeLocked: boolean
  libraryChips: LibraryChip[]
  onClearScope: () => void
  onSetScopeAll: () => void
  onRemoveLibrary: (libraryId: string) => void
  metadataEnabled: boolean
  metadataFilter: MetadataFilter | null
  filterOptions: MetadataFilterOptionsResponse | null
  filterScope: MetadataFilterScope
  onMetadataFilterChange: (filter: MetadataFilter | null) => void
  documentTypeChipLabel: string | undefined
  dateChip: string | undefined
  onRemoveDocumentTypeFilter: () => void
  onRemoveDateFilter: () => void
}

/**
 * The bar above the input: the action mark, the search scope as chips and the chat's core-field
 * filter. The next question searches exactly what the bar shows.
 */
export default function ChatScopeChipBar({
  selected,
  onClearSelected,
  disabled,
  scope,
  scopeLocked,
  libraryChips,
  onClearScope,
  onSetScopeAll,
  onRemoveLibrary,
  metadataEnabled,
  metadataFilter,
  filterOptions,
  filterScope,
  onMetadataFilterChange,
  documentTypeChipLabel,
  dateChip,
  onRemoveDocumentTypeFilter,
  onRemoveDateFilter,
}: ChatScopeChipBarProps) {
  return (
    <Box
      sx={{
        maxWidth: CHAT_MAX_WIDTH,
        mx: 'auto',
        mb: 1,
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 0.75,
      }}
    >
      {selected && (
        <Chip
          icon={<selected.source.Icon />}
          label={selected.source.markLabel(selected.mark)}
          size="small"
          variant="outlined"
          onDelete={disabled ? undefined : onClearSelected}
          aria-label={`Kennzeichnung ${selected.source.markLabel(selected.mark)} entfernen`}
          data-testid="used-prompt-chip"
        />
      )}
      {scope === 'all' && (
        <Chip
          icon={<AllInclusiveIcon />}
          label="@Space-Wissen"
          size="small"
          color="primary"
          onDelete={scopeLocked ? undefined : onClearScope}
          // The default delete icon carries aria-hidden from MUI, so the accessible name has to
          // sit on the chip itself rather than on that icon.
          aria-label="Referenz Space-Wissen entfernen"
        />
      )}
      {scope === 'libraries' &&
        libraryChips.map((chip) => {
          if (chip.kind === 'known') {
            return (
              <Chip
                key={chip.libraryId}
                label={chip.library.name}
                size="small"
                variant="filled"
                color="primary"
                onDelete={scopeLocked ? undefined : () => onRemoveLibrary(chip.libraryId)}
                aria-label={`Bibliotheksreferenz ${chip.library.name} entfernen`}
              />
            )
          }
          if (chip.kind === 'loading') {
            return (
              <Chip
                key={chip.libraryId}
                label="Bibliothek wird geladen …"
                size="small"
                variant="outlined"
                disabled
                aria-label="Bibliotheksreferenz wird geladen"
              />
            )
          }
          return (
            <Chip
              key={chip.libraryId}
              label="Nicht verfügbare Bibliothek"
              size="small"
              variant="outlined"
              color="warning"
              onDelete={scopeLocked ? undefined : () => onRemoveLibrary(chip.libraryId)}
              aria-label="Nicht verfügbare Bibliotheksreferenz entfernen"
            />
          )
        })}
      {scope === 'none' && (
        <>
          {/* The statement is a warning in itself; it needs no signal colour in the text
           (design guidelines 2.3). */}
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Antwortet ohne Dokumente.
          </Typography>
          <Chip
            icon={<AllInclusiveIcon />}
            label="@Space-Wissen nutzen"
            size="small"
            variant="outlined"
            onClick={scopeLocked ? undefined : onSetScopeAll}
            disabled={scopeLocked}
            // Named by its visible label (WCAG 2.5.3); what it does is the description.
            title="Wieder das Wissen des Space durchsuchen"
          />
        </>
      )}
      {scope !== 'none' && metadataEnabled && (
        <>
          {documentTypeChipLabel && (
            <Chip
              label={documentTypeChipLabel}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={scopeLocked ? undefined : onRemoveDocumentTypeFilter}
              aria-label="Filter nach Dokumentart entfernen"
              data-testid="metadata-filter-chip-document-type"
            />
          )}
          {dateChip && (
            <Chip
              label={dateChip}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={scopeLocked ? undefined : onRemoveDateFilter}
              aria-label="Filter nach Datum entfernen"
              data-testid="metadata-filter-chip-document-date"
            />
          )}
          {(metadataFilter?.libraryFields ?? []).map((condition) => (
            <Chip
              key={`${condition.libraryId}/${condition.fieldKey}`}
              label={libraryFieldChipLabel(condition, filterOptions)}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={
                scopeLocked
                  ? undefined
                  : () => {
                      if (metadataFilter)
                        onMetadataFilterChange(withoutLibraryField(metadataFilter, condition))
                    }
              }
              aria-label={`Filter nach ${libraryFieldLabel(condition, filterOptions)} entfernen`}
              data-testid="metadata-filter-chip-library-field"
            />
          ))}
          {(metadataFilter?.formatFields ?? []).map((condition) => (
            <Chip
              key={condition.fieldKey}
              label={formatFieldChipLabel(condition, filterOptions)}
              size="small"
              variant="outlined"
              color="secondary"
              onDelete={
                scopeLocked
                  ? undefined
                  : () => {
                      if (metadataFilter)
                        onMetadataFilterChange(withoutFormatField(metadataFilter, condition))
                    }
              }
              aria-label={`Filter nach ${formatFieldLabel(condition, filterOptions)} entfernen`}
              data-testid="metadata-filter-chip-format-field"
            />
          ))}
          <MetadataFilterPopover
            scope={filterScope}
            filter={metadataFilter}
            onChange={onMetadataFilterChange}
            disabled={scopeLocked}
          />
        </>
      )}
    </Box>
  )
}
