import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Fragment, useState } from 'react'
import type { PromptVariable, PromptVariableType } from '../../types/api'
import { fontFamily } from '../../theme/tokens'
import { promptVariableTypeLabel, promptVariableTypes } from '../../utils/labels'
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
    <TextField
      size="small"
      fullWidth
      multiline
      minRows={2}
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
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        Der Text verwendet keine eigenen Variablen. Eine Variable entsteht, sobald der Text einen
        Platzhalter wie {'{{aktenzeichen}}'} enthält.
      </Typography>
    )
  }
  return (
    <Box sx={{ overflowX: 'auto' }}>
      <Table size="small" aria-label="Variablen">
        <TableHead>
          <TableRow>
            <TableCell>Variable</TableCell>
            <TableCell>Beschriftung</TableCell>
            <TableCell>Typ</TableCell>
            <TableCell>Pflicht</TableCell>
            <TableCell>Vorbelegung</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {variables.map((variable) => (
            <Fragment key={variable.name}>
              <TableRow>
                <TableCell sx={{ fontFamily: fontFamily.mono, fontSize: 12.5 }}>
                  {`{{${variable.name}}}`}
                </TableCell>
                <TableCell sx={{ minWidth: 160 }}>
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
                </TableCell>
                <TableCell sx={{ minWidth: 170 }}>
                  <Select
                    size="small"
                    fullWidth
                    value={variable.type}
                    onChange={(e) =>
                      onChange(withType(variable, e.target.value as PromptVariableType))
                    }
                    aria-label={`Typ von ${variable.name}`}
                  >
                    {promptVariableTypes.map((type) => (
                      <MenuItem key={type} value={type}>
                        {promptVariableTypeLabel(type)}
                      </MenuItem>
                    ))}
                  </Select>
                </TableCell>
                <TableCell padding="checkbox">
                  <Checkbox
                    checked={variable.required}
                    onChange={(e) => onChange({ ...variable, required: e.target.checked })}
                    slotProps={{ input: { 'aria-label': `${variable.name} ist Pflicht` } }}
                  />
                </TableCell>
                <TableCell sx={{ minWidth: 160 }}>
                  <PromptVariableInput
                    variable={variable}
                    value={variable.defaultValue ?? ''}
                    onChange={(value) => onChange({ ...variable, defaultValue: value || null })}
                    ariaLabel={`Vorbelegung von ${variable.name}`}
                  />
                </TableCell>
              </TableRow>
              {variable.type === 'SELECT' && (
                <TableRow>
                  <TableCell />
                  <TableCell colSpan={4}>
                    <OptionsField
                      variable={variable}
                      onChange={(options) => onChange({ ...variable, options })}
                    />
                  </TableCell>
                </TableRow>
              )}
            </Fragment>
          ))}
        </TableBody>
      </Table>
    </Box>
  )
}
