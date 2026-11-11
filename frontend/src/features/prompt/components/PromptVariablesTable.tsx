import Checkbox from '@mui/material/Checkbox'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import type { PromptVariable, PromptVariableType } from '@/shared/api/types'
import { fontSize } from '@/shared/theme/tokens'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { promptVariableTypeLabel, promptVariableTypes } from './promptLabels'
import PromptVariableInput from './PromptVariableInput'

interface PromptVariablesTableProps {
  /** One row per variable the text uses, in order of use. */
  variables: PromptVariable[]
  onChange: (variable: PromptVariable) => void
}

function withType(variable: PromptVariable, type: PromptVariableType): PromptVariable {
  // A type change keeps what still fits: options only for a selection, and a default only while
  // it is still a valid value of the new type.
  const options = type === 'SELECT' ? (variable.options ?? []) : null
  const keepsDefault =
    type === variable.type ||
    (type !== 'SELECT' && type !== 'DATE' && variable.type !== 'SELECT' && variable.type !== 'DATE')
  return {
    ...variable,
    type,
    options,
    defaultValue: keepsDefault ? variable.defaultValue : null,
  }
}

function parseOptions(value: string): string[] {
  return value
    .split('\n')
    .map((option) => option.trim())
    .filter(Boolean)
}

/**
 * The options of a selection, one per line. The raw text is kept locally so an empty line being
 * typed survives; the definition receives the trimmed, non-empty lines.
 */
function OptionsField({
  variable,
  onChange,
}: {
  variable: PromptVariable
  onChange: (options: string[]) => void
}) {
  const [raw, setRaw] = useState(() => (variable.options ?? []).join('\n'))
  return (
    <FormField
      size="small"
      fullWidth
      multiline
      label="Auswahlwerte, einer je Zeile"
      value={raw}
      onChange={(e) => {
        setRaw(e.target.value)
        onChange(parseOptions(e.target.value))
      }}
      slotProps={{ htmlInput: { 'aria-label': `Auswahlwerte von ${variable.name}` } }}
    />
  )
}

/**
 * The definitions of a prompt's variables: label, type, whether an answer is required, a default
 * and - for a selection - its options, one per line. The rows follow the text; a variable is
 * added by writing `{{name}}` into it, not here.
 */
export default function PromptVariablesTable({ variables, onChange }: PromptVariablesTableProps) {
  if (variables.length === 0) {
    return (
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
        Der Text verwendet keine eigenen Variablen. Eine Variable entsteht, sobald der Text einen
        Platzhalter wie {'{{aktenzeichen}}'} enthält.
      </Typography>
    )
  }
  return (
    <AdminList
      label="Variablen"
      rows={variables}
      rowKey={(variable) => variable.name}
      rowLabel={(variable) => variable.name}
      minWidth={640}
      columns={[
        {
          id: 'name',
          head: 'Variable',
          width: '20%',
          cell: (variable) => <ListText mono>{`{{${variable.name}}}`}</ListText>,
        },
        {
          id: 'label',
          head: 'Beschriftung',
          cell: (variable) => (
            <TextField
              size="small"
              fullWidth
              value={variable.label}
              onChange={(e) => onChange({ ...variable, label: e.target.value })}
              slotProps={{
                htmlInput: {
                  'aria-label': `Beschriftung von ${variable.name}`,
                  maxLength: 255,
                },
              }}
            />
          ),
        },
        {
          id: 'type',
          head: 'Typ',
          width: '22%',
          cell: (variable) => (
            <Select
              size="small"
              fullWidth
              value={variable.type}
              onChange={(e) => onChange(withType(variable, e.target.value as PromptVariableType))}
              aria-label={`Typ von ${variable.name}`}
            >
              {promptVariableTypes.map((type) => (
                <MenuItem key={type} value={type}>
                  {promptVariableTypeLabel(type)}
                </MenuItem>
              ))}
            </Select>
          ),
        },
        {
          id: 'required',
          head: 'Pflicht',
          width: 72,
          cell: (variable) => (
            <Checkbox
              checked={variable.required}
              onChange={(e) => onChange({ ...variable, required: e.target.checked })}
              slotProps={{ input: { 'aria-label': `${variable.name} ist Pflicht` } }}
            />
          ),
        },
        {
          id: 'default',
          head: 'Vorbelegung',
          width: '24%',
          wrap: true,
          cell: (variable) => (
            <PromptVariableInput
              variable={variable}
              value={variable.defaultValue ?? ''}
              onChange={(value) => onChange({ ...variable, defaultValue: value || null })}
              ariaLabel={`Vorbelegung von ${variable.name}`}
            />
          ),
        },
      ]}
      rowDetail={(variable) =>
        variable.type === 'SELECT' ? (
          <OptionsField
            variable={variable}
            onChange={(options) => onChange({ ...variable, options })}
          />
        ) : null
      }
    />
  )
}
