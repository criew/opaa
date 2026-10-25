import { useState, type CSSProperties } from 'react'
import { currentMockScenario } from '@/shared/mocks/mockScenario'
import { currentUiLabVariant, UI_LAB_VARIANTS } from '@/shared/uiLabVariant'
import { ENTRY_GROUPS, NARROW_WIDTH, SCENARIOS, WIDTH_PARAM } from './prototypeConfig'

const COLLAPSED_KEY = 'opaa.prototypeBar.collapsed'

// Deliberately outside the product's palette and theme: the bar must never read as part of OPAA.
const BAR_BG = '#3b1f5c'
const BAR_FG = '#ffffff'
const ACCENT = '#f6c445'

const barStyle: CSSProperties = {
  position: 'fixed',
  right: 12,
  bottom: 12,
  zIndex: 2147483000,
  background: BAR_BG,
  color: BAR_FG,
  font: '13px/1.4 system-ui, sans-serif',
  borderRadius: 10,
  boxShadow: '0 4px 16px rgba(0,0,0,.35)',
  border: `2px solid ${ACCENT}`,
  maxWidth: 'calc(100vw - 24px)',
}

const fieldStyle: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 2 }

const controlStyle: CSSProperties = {
  font: 'inherit',
  padding: '3px 4px',
  borderRadius: 4,
  border: '1px solid #cbb8e6',
  background: '#ffffff',
  color: '#1a1a1a',
  maxWidth: 220,
}

const toggleStyle: CSSProperties = {
  font: 'inherit',
  fontWeight: 600,
  background: 'transparent',
  color: BAR_FG,
  border: 'none',
  padding: '6px 10px',
  cursor: 'pointer',
}

function readCollapsed() {
  try {
    return window.sessionStorage.getItem(COLLAPSED_KEY) === 'true'
  } catch {
    return false
  }
}

/** Reloads the page with one search parameter changed; the hash (the app's route) stays. */
function reloadWith(name: string, value: string | null) {
  const url = new URL(window.location.href)
  if (value === null) url.searchParams.delete(name)
  else url.searchParams.set(name, value)
  window.location.assign(url.href)
}

interface PrototypeBarProps {
  /** Navigates the app, which runs in this document or in the narrow frame. */
  navigate: (path: string) => void
}

/** The floating control bar of the static UI-lab prototype: scenario, entry, width, variant. */
export default function PrototypeBar({ navigate }: PrototypeBarProps) {
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const narrow = new URLSearchParams(window.location.search).get(WIDTH_PARAM) === `${NARROW_WIDTH}`
  const variant = currentUiLabVariant()

  const toggle = () => {
    setCollapsed(!collapsed)
    try {
      window.sessionStorage.setItem(COLLAPSED_KEY, String(!collapsed))
    } catch {
      // the bar still toggles, it just forgets the choice on reload
    }
  }

  return (
    <aside aria-label="Prototyp-Leiste" style={barStyle} data-prototype-bar="">
      <button
        type="button"
        onClick={toggle}
        aria-expanded={!collapsed}
        style={toggleStyle}
        title="Steuerung des Prototyps, nicht Teil von OPAA"
      >
        {collapsed ? '◆ Prototyp' : '◆ Prototyp – einklappen'}
      </button>
      {!collapsed && (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 10,
            padding: '0 10px 10px',
            alignItems: 'flex-end',
          }}
        >
          <label style={fieldStyle}>
            Szenario
            <select
              style={controlStyle}
              value={currentMockScenario()}
              onChange={(event) => reloadWith('mockScenario', event.target.value)}
            >
              {SCENARIOS.map((scenario) => (
                <option key={scenario.id} value={scenario.id}>
                  {scenario.label}
                </option>
              ))}
            </select>
          </label>
          <label style={fieldStyle}>
            Einstieg
            <select
              style={controlStyle}
              value=""
              onChange={(event) => {
                if (event.target.value) navigate(event.target.value)
              }}
            >
              <option value="">Seite wählen …</option>
              {ENTRY_GROUPS.map((group) => (
                <optgroup key={group.label} label={group.label}>
                  {group.entries.map((entry) => (
                    <option key={entry.path} value={entry.path}>
                      {entry.label}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
          <label style={fieldStyle}>
            Breite
            <select
              style={controlStyle}
              value={narrow ? 'narrow' : 'normal'}
              onChange={(event) =>
                reloadWith(WIDTH_PARAM, event.target.value === 'narrow' ? `${NARROW_WIDTH}` : null)
              }
            >
              <option value="normal">Normal</option>
              <option value="narrow">{NARROW_WIDTH} px</option>
            </select>
          </label>
          {UI_LAB_VARIANTS.length > 0 && (
            <label style={fieldStyle}>
              Variante
              <select
                style={controlStyle}
                value={variant ?? ''}
                onChange={(event) => reloadWith('variant', event.target.value || 'none')}
              >
                <option value="">Ohne Variante</option>
                {UI_LAB_VARIANTS.map((entry) => (
                  <option key={entry.id} value={entry.id}>
                    {entry.label}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
      )}
    </aside>
  )
}
