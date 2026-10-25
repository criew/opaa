import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import CloseIcon from '@mui/icons-material/Close'
import SearchIcon from '@mui/icons-material/Search'
import Typography from '@mui/material/Typography'

/**
 * The one content width of every tab of a detail page; the Reiter „Quelle“ uses the same value
 * (`CONTENT_WIDTH` of the source rows). Tables may run wider but start at the same left edge.
 */
export const TAB_CONTENT_WIDTH = 880

interface TabSectionProps {
  /** Sentence-case heading, weight 600 - the one heading style of every tab. */
  title?: string
  /** The section's own action (a text button such as „Freigeben“) at the right content edge. */
  action?: ReactNode
  /** A tool row under the heading, e.g. the search of a long list. */
  tools?: ReactNode
  headingLevel?: 'h2' | 'h3'
  /** Lets a table run beyond the content width; it still starts at the same left edge. */
  wide?: boolean
  children: ReactNode
}

/**
 * A section of a detail tab: heading and action on one line, the action at the shared right
 * edge, below it the content without a box. Without a title the action still sits at the edge.
 */
export default function TabSection({
  title,
  action,
  tools,
  headingLevel = 'h2',
  wide = false,
  children,
}: TabSectionProps) {
  const headId = useId()
  return (
    <Box
      component="section"
      aria-labelledby={title ? headId : undefined}
      sx={{ mb: 4, maxWidth: wide ? 'none' : TAB_CONTENT_WIDTH }}
    >
      {(title || action) && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'baseline',
            justifyContent: title ? 'space-between' : 'flex-end',
            gap: 2,
            maxWidth: TAB_CONTENT_WIDTH,
            mb: 1.5,
          }}
        >
          {title && (
            <Typography
              id={headId}
              component={headingLevel}
              sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary', m: 0 }}
            >
              {title}
            </Typography>
          )}
          {/* text actions end exactly at the content edge, like „Ändern“ in the source rows */}
          {action && (
            <Box sx={{ flex: 'none', '& .MuiButton-text': { px: 0, minWidth: 0 } }}>{action}</Box>
          )}
        </Box>
      )}
      {tools && <Box sx={{ maxWidth: TAB_CONTENT_WIDTH, mb: 1.5 }}>{tools}</Box>}
      {children}
    </Box>
  )
}

/**
 * The one search field of a tab's list: magnifier in front, reset at the end, 280 px wide and the
 * height of every small field; the documents, grants and spaces use the same.
 */
export function ListSearchField({
  value,
  onChange,
  placeholder,
  ariaLabel,
}: {
  value: string
  onChange: (value: string) => void
  placeholder: string
  ariaLabel: string
}) {
  return (
    <TextField
      size="small"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      placeholder={placeholder}
      sx={{ width: { xs: '100%', sm: 280 } }}
      slotProps={{
        input: {
          startAdornment: (
            <InputAdornment position="start">
              <SearchIcon sx={{ fontSize: 16 }} />
            </InputAdornment>
          ),
          endAdornment: value ? (
            <InputAdornment position="end">
              <IconButton size="small" aria-label="Suche zurücksetzen" onClick={() => onChange('')}>
                <CloseIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </InputAdornment>
          ) : undefined,
        },
        htmlInput: { 'aria-label': ariaLabel },
      }}
    />
  )
}

/**
 * The empty state of every tab: „Noch keine …“ and right after it the one action that fills it,
 * as a text button in the same line. With an empty state shown, the section has no action above.
 */
export function EmptyLine({ text, action }: { text: string; action?: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'baseline',
        columnGap: 1.5,
        flexWrap: 'wrap',
        '& .MuiButton-text': { px: 0, minWidth: 0, fontSize: 13.5 },
      }}
    >
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>{text}</Typography>
      {action}
    </Box>
  )
}
