import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { DocumentTemplatePlaceholder } from '@/shared/api/types'
import { fontFamily } from '@/shared/theme/tokens'

const KIND_LABELS: Record<DocumentTemplatePlaceholder['kind'], string> = {
  FIELD: 'Feld',
  LIST: 'Liste',
  CONDITION: 'Bedingung',
}

function syntaxOf(placeholder: DocumentTemplatePlaceholder): string {
  switch (placeholder.kind) {
    case 'LIST':
      return `{% for eintrag in ${placeholder.name} %}`
    case 'CONDITION':
      return `{% if ${placeholder.name} %}`
    default:
      return `{{ ${placeholder.name} }}`
  }
}

/** The placeholders read from a template file, as a table of name, kind and parts. */
export default function PlaceholderList({
  placeholders,
}: {
  placeholders: DocumentTemplatePlaceholder[]
}) {
  if (placeholders.length === 0) {
    return (
      <Typography sx={{ color: 'text.secondary' }}>
        Die Datei enthält keine Platzhalter. Agenten können sie nur unverändert ablegen.
      </Typography>
    )
  }
  return (
    <Box
      component="table"
      sx={{
        width: '100%',
        borderCollapse: 'collapse',
        fontSize: 13.5,
        '& th, & td': {
          textAlign: 'left',
          py: 1,
          pr: 2,
          borderBottom: 1,
          borderColor: 'divider',
          verticalAlign: 'top',
        },
        '& th': { fontSize: 12, fontWeight: 600, color: 'text.secondary' },
      }}
    >
      <caption style={{ position: 'absolute', left: -10000 }}>Platzhalter der Vorlage</caption>
      <thead>
        <tr>
          <th scope="col">Platzhalter</th>
          <th scope="col">Art</th>
          <th scope="col">Teile je Eintrag</th>
        </tr>
      </thead>
      <tbody>
        {placeholders.map((placeholder) => (
          <tr key={placeholder.name}>
            <td>
              <Box component="span" sx={{ fontWeight: 600 }}>
                {placeholder.name}
              </Box>
              <Box
                component="div"
                sx={{ fontFamily: fontFamily.mono, fontSize: 12, color: 'text.secondary' }}
              >
                {syntaxOf(placeholder)}
              </Box>
            </td>
            <td>{KIND_LABELS[placeholder.kind]}</td>
            <td>{placeholder.fields.length > 0 ? placeholder.fields.join(', ') : '–'}</td>
          </tr>
        ))}
      </tbody>
    </Box>
  )
}
