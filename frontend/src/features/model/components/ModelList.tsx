import Box from '@mui/material/Box'
import type { LlmModelResponse } from '@/shared/api/types'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import ModelRowMenu from './ModelRowMenu'
import { TOOL_CAPABILITY_LABELS, toolCapabilityBasisLine } from './toolCapability'

interface ModelListProps {
  models: LlmModelResponse[]
  onEdit: (model: LlmModelResponse) => void
}

/** Dot plus word - the colour never carries alone, the same rule as in `StatusLine`. */
function StateCell({ active }: { active: boolean }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}>
      <Box
        aria-hidden
        sx={{
          width: 7,
          height: 7,
          borderRadius: '50%',
          flex: 'none',
          bgcolor: active ? 'success.main' : 'text.disabled',
          transform: 'translateY(-1px)',
        }}
      />
      <Box component="span">{active ? 'Aktiv' : 'Nicht aktiv'}</Box>
    </Box>
  )
}

/** The tool capability and, once checked, how and when — never who. */
function ToolCapabilityCell({ model }: { model: LlmModelResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <ListText>{TOOL_CAPABILITY_LABELS[model.toolCapability.value]}</ListText>
      <ListText secondary>{toolCapabilityBasisLine(model.toolCapability)}</ListText>
    </Box>
  )
}

/**
 * The chat models as a list — one row per model, the actions in the row menu.
 *
 * Temperature and maximum response length deliberately have no column: they are fine tuning
 * nobody skims a list for, and are in the dialog. An overview that shows everything shows
 * nothing.
 */
export default function ModelList({ models, onEdit }: ModelListProps) {
  return (
    <AdminList
      label="Chat-Modelle"
      rows={models}
      rowKey={(model) => model.id}
      rowLabel={(model) => model.displayName}
      minWidth={860}
      columns={[
        {
          id: 'model',
          head: 'Modell',
          cell: (model) => (
            <Box sx={{ minWidth: 0 }}>
              <ListText strong>{model.displayName}</ListText>
              <ListText mono secondary>
                {model.modelIdentifier}
              </ListText>
            </Box>
          ),
        },
        {
          id: 'endpoint',
          head: 'Endpunkt',
          width: '28%',
          cell: (model) => <ListText mono>{model.baseUrl}</ListText>,
        },
        {
          id: 'state',
          head: 'Zustand',
          width: '13%',
          cell: (model) => <StateCell active={model.active} />,
        },
        {
          id: 'access',
          head: 'Zugang',
          width: '13%',
          cell: (model) => (model.apiKeySet ? 'Schlüssel hinterlegt' : 'Ohne Schlüssel'),
        },
        {
          id: 'tools',
          head: 'Werkzeuge',
          width: '18%',
          cell: (model) => <ToolCapabilityCell model={model} />,
        },
      ]}
      rowActions={(model) => <ModelRowMenu model={model} onEdit={onEdit} />}
    />
  )
}
