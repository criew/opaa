import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import PaletteOutlinedIcon from '@mui/icons-material/PaletteOutlined'
import { renderWithProviders } from '../../test/test-utils'
import { contentWidth } from '../theme/tokens'
import { formatAreaCount } from './areaCount'
import AreaPageCount from './AreaPageCount'
import AreaPageHeader from './AreaPageHeader'
import CreateButton from './CreateButton'

/** Production source text of the whole app, read in by Vite at build time (`?raw`). */
const SOURCES = Object.entries(
  import.meta.glob('../../{features,app,shared}/**/*.{ts,tsx}', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>,
)
  // Keys are relative to this file; name every file from `src/` instead.
  .map(
    ([path, text]) =>
      [
        path.startsWith('../../')
          ? path.slice(6)
          : path.startsWith('../')
            ? `shared/${path.slice(3)}`
            : `shared/ui/${path.slice(2)}`,
        text,
      ] as const,
  )
  .filter(([path]) => !/\.test\.tsx?$/.test(path) && !path.includes('/mocks/'))

function offenders(paths: (path: string) => boolean, pattern: RegExp): string[] {
  return SOURCES.filter(([path]) => paths(path)).flatMap(([path, text]) =>
    text
      .split('\n')
      .map((line, index) => (pattern.test(line) ? `${path}:${index + 1}: ${line.trim()}` : null))
      .filter((hit): hit is string => hit !== null),
  )
}

describe('page grid (guidelines 4.6 and 5.1)', () => {
  it('has the three content widths as tokens', () => {
    expect(contentWidth).toEqual({ form: 720, detail: 880, list: 1180 })
  })

  /**
   * The plus icon marks the one action that creates an object. It comes from `CreateButton`,
   * which shows it in the page header or, as `placement="empty"`, in an empty state; the overview
   * pages build their header themselves. The sidebar's plus is an icon-only button, no start icon.
   */
  it('puts a plus icon before a label only in CreateButton and the overview header', () => {
    const allowed = ['shared/ui/CreateButton.tsx', 'shared/ui/overview/OverviewPage.tsx']
    expect(
      offenders((path) => !allowed.includes(path), /startIcon=\{\s*<Add(Icon|Outlined|Rounded)?\b/),
    ).toEqual([])
  })

  /**
   * A theme names a width (`contentWidth`, `fieldWidth`, `readingWidth`), it never writes one:
   * five page widths had grown from numbers in single pages. Sign-in and installation pages stand
   * outside the app frame and keep their own layout files.
   */
  it('writes no max width as a number under features/ and app/areas/', () => {
    const ownLayout = ['features/auth/', 'features/installation/']
    expect(
      offenders(
        (path) =>
          (path.startsWith('features/') || path.startsWith('app/areas/')) &&
          !ownLayout.some((prefix) => path.startsWith(prefix)),
        /maxWidth\s*[:=]\s*\{?\s*(\d+\b(?!%)|['"`]\d+(px|ch|rem|em)\b)/,
      ),
    ).toEqual([])
  })

  it('keeps counters out of the tabs of an area page', () => {
    expect(offenders((path) => path === 'shared/ui/AreaTabs.tsx', /\bcount\??:/)).toEqual([])
  })
})

describe('formatAreaCount', () => {
  const unit = ['Konto', 'Konten'] as const

  it('names the total with its noun', () => {
    expect(formatAreaCount({ total: 1, unit })).toBe('1 Konto')
    expect(formatAreaCount({ total: 40, unit })).toBe('40 Konten')
    expect(formatAreaCount({ total: 0, unit })).toBe('0 Konten')
  })

  it('reads „12 von 40“ while a filter narrows the list', () => {
    expect(formatAreaCount({ total: 40, filtered: 12, unit })).toBe('12 von 40 Konten')
    expect(formatAreaCount({ total: 40, filtered: 40, unit })).toBe('40 Konten')
  })
})

describe('header slots', () => {
  function Page({ tab }: { tab: 'accounts' | 'settings' }) {
    return (
      <>
        <AreaPageHeader icon={PaletteOutlinedIcon} title="Benutzer" description="Alle Konten." />
        <div data-testid="content">
          {tab === 'accounts' && (
            <>
              <AreaPageCount total={40} filtered={12} unit={['Konto', 'Konten']} />
              <CreateButton>Konto anlegen</CreateButton>
            </>
          )}
        </div>
      </>
    )
  }

  it('shows count and create button of the tab content in the title row', () => {
    renderWithProviders(<Page tab="accounts" />)

    const titleRow = screen.getByRole('heading', { level: 1 }).parentElement!.parentElement!
    expect(within(titleRow).getByText('12 von 40 Konten')).toBeInTheDocument()
    expect(within(titleRow).getByRole('button', { name: 'Konto anlegen' })).toBeInTheDocument()
    expect(screen.getByTestId('content')).toBeEmptyDOMElement()
  })

  it('takes both away with the tab that brought them', () => {
    const { rerender } = renderWithProviders(<Page tab="accounts" />)
    rerender(<Page tab="settings" />)

    expect(screen.queryByRole('button', { name: 'Konto anlegen' })).toBeNull()
    expect(screen.queryByText('12 von 40 Konten')).toBeNull()
  })

  it('leaves both in place where no header is on the page', () => {
    renderWithProviders(
      <div data-testid="content">
        <AreaPageCount total={1} unit={['Konto', 'Konten']} />
        <CreateButton>Konto anlegen</CreateButton>
      </div>,
    )

    const content = screen.getByTestId('content')
    expect(within(content).getByText('1 Konto')).toBeInTheDocument()
    expect(within(content).getByRole('button', { name: 'Konto anlegen' })).toBeInTheDocument()
  })

  it('keeps the create button of an empty state in place and secondary', () => {
    renderWithProviders(
      <>
        <AreaPageHeader icon={PaletteOutlinedIcon} title="Benutzer" description="Alle Konten." />
        <div data-testid="empty">
          <CreateButton placement="empty">Konto anlegen</CreateButton>
        </div>
      </>,
    )

    const button = within(screen.getByTestId('empty')).getByRole('button', {
      name: 'Konto anlegen',
    })
    expect(button).toHaveClass('MuiButton-outlined')
  })

  it('explains a disabled create button', async () => {
    renderWithProviders(
      <CreateButton disabled disabledReason="Der Zugriffsweg ist ausgeschaltet.">
        Token erzeugen
      </CreateButton>,
    )

    await userEvent.hover(screen.getByRole('button', { name: 'Token erzeugen' }).parentElement!)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'Der Zugriffsweg ist ausgeschaltet.',
    )
  })
})
