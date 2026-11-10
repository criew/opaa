import Box from '@mui/material/Box'
import ListItemButton from '@mui/material/ListItemButton'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import { fontSize, iconSize } from '@/shared/theme/tokens'
import MetaBadge from '@/shared/ui/MetaBadge'
import type { MentionSuggestion } from './chatInputText'
import { mentionBadge, mentionName } from './mentionSuggestionText'

interface MentionOptionProps {
  suggestion: MentionSuggestion
  optionId: string
  highlighted: boolean
  query: string
  onHighlight: () => void
  onSelect: () => void
}

/**
 * One entry of the '@' menu. An agent is set apart from knowledge by its icon, its badge and its
 * accessible name ("Agent …"), not by colour alone.
 */
export default function MentionOption({
  suggestion,
  optionId,
  highlighted,
  query,
  onHighlight,
  onSelect,
}: MentionOptionProps) {
  const name = mentionName(suggestion)
  const badge = mentionBadge(suggestion)
  const matchIndex = query.length > 0 ? name.toLowerCase().indexOf(query.toLowerCase()) : -1
  const iconSx = { fontSize: iconSize.sm, color: 'text.primary' }
  return (
    <ListItemButton
      component="li"
      id={optionId}
      role="option"
      aria-selected={highlighted}
      aria-label={suggestion.kind === 'agent' ? `Agent ${name}, ${badge}` : undefined}
      selected={highlighted}
      onMouseDown={(e) => e.preventDefault()}
      onMouseEnter={onHighlight}
      onClick={onSelect}
      sx={{
        gap: 1.25,
        py: 1,
        '&.Mui-selected': {
          bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
        },
      }}
    >
      {suggestion.kind === 'all' ? (
        <AllInclusiveIcon sx={iconSx} />
      ) : suggestion.kind === 'agent' ? (
        <SmartToyOutlinedIcon sx={iconSx} />
      ) : (
        <MenuBookOutlinedIcon sx={iconSx} />
      )}
      <Typography
        component="span"
        noWrap
        sx={{ flex: 1, fontSize: fontSize.text, color: 'text.primary' }}
      >
        {matchIndex >= 0 ? (
          <>
            {name.slice(0, matchIndex)}
            <Box component="strong" sx={{ fontWeight: 600 }}>
              {name.slice(matchIndex, matchIndex + query.length)}
            </Box>
            {name.slice(matchIndex + query.length)}
          </>
        ) : (
          name
        )}
      </Typography>
      <Box component="span" sx={{ flex: 'none', display: 'inline-flex' }}>
        <MetaBadge>{badge}</MetaBadge>
      </Box>
    </ListItemButton>
  )
}
