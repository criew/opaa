import { useId, useState, type ComponentType, type Ref } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { SvgIconProps } from '@mui/material/SvgIcon'
import type { SxProps, Theme } from '@mui/material/styles'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'

export interface RowMenuItem {
  label: string
  /** Runs after the menu has closed, so a confirmation never stacks on the open menu. */
  onClick?: () => void
  /** Leads elsewhere instead: the entry is a router link. */
  to?: string
  /** All entries of one menu carry an icon, or none does (guidelines 5.6). */
  icon?: ComponentType<SvgIconProps>
  /** Irreversible (confirmation tone `danger`): danger text, last, set apart by a divider. */
  danger?: boolean
  disabled?: boolean
  /** Why a disabled entry is unavailable: shown once in the menu and read out with the entry. */
  disabledReason?: string
}

/** The one accessible name of every „⋯" trigger (guidelines 5.6). */
function moreActionsLabel(name: string, detail?: string): string {
  return `Weitere Aktionen für „${name}“${detail ? ` ${detail}` : ''}`
}

const HINT_SX = { px: 2, py: 1, maxWidth: 340 } as const
const HINT_TEXT_SX = { fontSize: 12, color: 'text.secondary' } as const

/**
 * „⋯": the further actions of a row, tile or detail page, the same everywhere. Trigger, names
 * and keyboard operation (arrows, Enter, Escape, focus back on the trigger) live here; callers
 * only list their entries. Destructive entries move to the end behind a divider.
 */
export default function RowMenu({
  name,
  detail,
  items,
  note,
  disabled = false,
  dense = false,
  triggerRef,
  triggerSx,
  onOpenChange,
}: {
  /** The object's name as the row shows it; the label reads „Weitere Aktionen für „<name>““. */
  name: string
  /** Distinguishes rows of the same name, after the quotes, e.g. „von Maria Musterfrau“. */
  detail?: string
  /** Falsy entries are left out, so a caller can list conditional entries in place. */
  items: ReadonlyArray<RowMenuItem | false | null | undefined>
  /** A standing hint below the entries. */
  note?: string
  /** While an action of the row runs. */
  disabled?: boolean
  /** The compact trigger of dense lists (sidebar): 16 px instead of 20 px. */
  dense?: boolean
  triggerRef?: Ref<HTMLButtonElement>
  triggerSx?: SxProps<Theme>
  onOpenChange?: (open: boolean) => void
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const id = useId()
  const buttonId = `${id}-trigger`
  const menuId = `${id}-menu`
  const open = anchor !== null
  const label = moreActionsLabel(name, detail)

  const present = items.filter((item): item is RowMenuItem => Boolean(item))
  const ordered = [
    ...present.filter((item) => !item.danger),
    ...present.filter((item) => item.danger),
  ]
  const withIcons = ordered.some((item) => item.icon)
  const reasons = [
    ...new Set(
      ordered.flatMap((item) =>
        item.disabled && item.disabledReason ? [item.disabledReason] : [],
      ),
    ),
  ]
  const reasonId = (reason: string) => `${id}-reason-${reasons.indexOf(reason)}`
  // a reason stands after the last entry it concerns, so entries sharing one show it once
  const lastWithReason = new Map(
    ordered.flatMap((item, index) =>
      item.disabled && item.disabledReason ? [[item.disabledReason, index] as const] : [],
    ),
  )

  function setOpen(element: HTMLElement | null) {
    setAnchor(element)
    onOpenChange?.(element !== null)
  }

  return (
    <>
      <Tooltip title="Weitere Aktionen">
        {/* the span keeps the tooltip on a disabled trigger */}
        <Box component="span" sx={{ display: 'inline-flex' }}>
          <IconButton
            id={buttonId}
            ref={triggerRef}
            size="small"
            edge="end"
            aria-label={label}
            aria-haspopup="menu"
            aria-expanded={open}
            aria-controls={open ? menuId : undefined}
            disabled={disabled}
            onClick={(event) => setOpen(event.currentTarget)}
            sx={[
              dense && { p: 0.5, borderRadius: '4px' },
              ...(Array.isArray(triggerSx) ? triggerSx : triggerSx ? [triggerSx] : []),
            ]}
          >
            <MoreHorizIcon sx={{ fontSize: dense ? 16 : 20 }} />
          </IconButton>
        </Box>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={open}
        onClose={() => setOpen(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ list: { id: menuId, 'aria-labelledby': buttonId } }}
      >
        {ordered.flatMap((item, index) => {
          const Icon = item.icon
          const reason = item.disabled ? item.disabledReason : undefined
          const danger = item.danger && !item.disabled
          const startsDanger = item.danger && index > 0 && !ordered[index - 1].danger
          const link = item.to && !item.disabled ? { component: RouterLink, to: item.to } : {}
          // MenuList takes no fragments: divider, entry and reason are siblings in one list
          return [
            startsDanger && <Divider key={`${item.label}-divider`} />,
            <MenuItem
              key={item.label}
              {...link}
              disabled={item.disabled}
              // no wrapper inside the menu (`aria-required-children`): the reason hangs on the
              // entry as title and description
              title={reason}
              aria-describedby={reason ? reasonId(reason) : undefined}
              data-danger={danger ? 'true' : undefined}
              onClick={() => {
                setOpen(null)
                item.onClick?.()
              }}
              sx={{ fontSize: 14, ...(danger && { color: 'error.main' }) }}
            >
              {withIcons && (
                <ListItemIcon sx={danger ? { color: 'error.main' } : undefined}>
                  {Icon && <Icon fontSize="small" />}
                </ListItemIcon>
              )}
              {item.label}
            </MenuItem>,
            reason && lastWithReason.get(reason) === index && (
              <Box
                key={`${item.label}-reason`}
                component="li"
                role="presentation"
                id={reasonId(reason)}
                sx={HINT_SX}
              >
                <Typography component="span" sx={HINT_TEXT_SX}>
                  {reason}
                </Typography>
              </Box>
            ),
          ].filter(Boolean)
        })}
        {note && <Divider key="note-divider" />}
        {note && (
          <Box key="note" component="li" role="presentation" sx={HINT_SX}>
            <Typography component="span" sx={HINT_TEXT_SX}>
              {note}
            </Typography>
          </Box>
        )}
      </Menu>
    </>
  )
}
