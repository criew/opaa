import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import ChoiceTileGroup, { type ChoiceTile } from './ChoiceTileGroup'

type Fruit = 'apple' | 'pear' | 'plum' | 'cherry'

const tiles: ChoiceTile<Fruit>[] = [
  { value: 'apple', label: 'Apfel', description: 'Rund und rot', icon: <span>A</span> },
  { value: 'pear', label: 'Birne', icon: <span>B</span>, disabledReason: 'Nicht vorrätig' },
  { value: 'plum', label: 'Pflaume', icon: <span>P</span> },
  { value: 'cherry', label: 'Kirsche', icon: <span>K</span> },
]

function Single({ initial = 'apple' }: { initial?: Fruit | null }) {
  const [value, setValue] = useState<Fruit | null>(initial)
  return (
    <ChoiceTileGroup<Fruit>
      aria-label="Obst wählen"
      tiles={tiles}
      value={value}
      onChange={setValue}
    />
  )
}

function Multiple() {
  const [value, setValue] = useState<Fruit[]>(['plum'])
  return (
    <ChoiceTileGroup<Fruit>
      multiple
      aria-label="Obstkorb"
      tiles={tiles}
      value={value}
      onChange={setValue}
    />
  )
}

describe('ChoiceTileGroup (guidelines 5.11)', () => {
  describe('single choice', () => {
    it('is a named radio group whose tiles carry name, sentence and the locked reason', () => {
      renderWithProviders(<Single />)

      expect(screen.getByRole('radiogroup', { name: 'Obst wählen' })).toBeInTheDocument()
      const apple = screen.getByRole('radio', { name: /Apfel/ })
      expect(apple).toHaveAttribute('aria-checked', 'true')
      expect(apple).toHaveTextContent('Rund und rot')
      const pear = screen.getByRole('radio', { name: /Birne/ })
      expect(pear).toBeDisabled()
      expect(pear).toHaveTextContent('Nicht vorrätig')
    })

    it('has one tab stop and moves choice and focus with the arrow keys, skipping locked tiles', async () => {
      const user = userEvent.setup()
      renderWithProviders(<Single />)

      expect(screen.getByRole('radio', { name: /Apfel/ })).toHaveAttribute('tabindex', '0')
      expect(screen.getByRole('radio', { name: /Pflaume/ })).toHaveAttribute('tabindex', '-1')

      await user.tab()
      expect(screen.getByRole('radio', { name: /Apfel/ })).toHaveFocus()
      await user.keyboard('{ArrowDown}')
      const plum = screen.getByRole('radio', { name: /Pflaume/ })
      expect(plum).toHaveAttribute('aria-checked', 'true')
      expect(plum).toHaveFocus()

      await user.keyboard('{ArrowRight}{ArrowRight}')
      expect(screen.getByRole('radio', { name: /Apfel/ })).toHaveAttribute('aria-checked', 'true')
      await user.keyboard('{ArrowLeft}')
      expect(screen.getByRole('radio', { name: /Kirsche/ })).toHaveFocus()
      await user.keyboard('{Home}')
      expect(screen.getByRole('radio', { name: /Apfel/ })).toHaveAttribute('aria-checked', 'true')
      await user.keyboard('{End}')
      expect(screen.getByRole('radio', { name: /Kirsche/ })).toHaveAttribute('aria-checked', 'true')
    })

    it('chooses with Space and Enter and by click, never a locked tile', async () => {
      const user = userEvent.setup()
      renderWithProviders(<Single />)

      screen.getByRole('radio', { name: /Kirsche/ }).focus()
      await user.keyboard(' ')
      expect(screen.getByRole('radio', { name: /Kirsche/ })).toHaveAttribute('aria-checked', 'true')
      screen.getByRole('radio', { name: /Pflaume/ }).focus()
      await user.keyboard('{Enter}')
      expect(screen.getByRole('radio', { name: /Pflaume/ })).toHaveAttribute('aria-checked', 'true')

      await user.click(screen.getByRole('radio', { name: /Birne/ }))
      expect(screen.getByRole('radio', { name: /Birne/ })).toHaveAttribute('aria-checked', 'false')
      expect(screen.getByRole('radio', { name: /Pflaume/ })).toHaveAttribute('aria-checked', 'true')
    })

    it('keeps the group reachable by keyboard while nothing is chosen yet', async () => {
      const user = userEvent.setup()
      renderWithProviders(<Single initial={null} />)

      expect(screen.getByRole('radio', { name: /Apfel/ })).toHaveAttribute('tabindex', '0')
      await user.tab()
      await user.keyboard('{ArrowDown}')
      expect(screen.getByRole('radio', { name: /Apfel/ })).toHaveAttribute('aria-checked', 'true')
    })
  })

  describe('multiple choice', () => {
    it('is a named group of checkboxes, each its own tab stop, toggled with Space', async () => {
      const user = userEvent.setup()
      renderWithProviders(<Multiple />)

      expect(screen.getByRole('group', { name: 'Obstkorb' })).toBeInTheDocument()
      expect(screen.queryByRole('radio')).not.toBeInTheDocument()
      expect(screen.getByRole('checkbox', { name: /Pflaume/ })).toBeChecked()

      await user.tab()
      expect(screen.getByRole('checkbox', { name: /Apfel/ })).toHaveFocus()
      await user.keyboard(' ')
      expect(screen.getByRole('checkbox', { name: /Apfel/ })).toBeChecked()
      // The locked pear is no tab stop; the next one is the plum.
      await user.tab()
      expect(screen.getByRole('checkbox', { name: /Pflaume/ })).toHaveFocus()
      await user.keyboard(' ')
      expect(screen.getByRole('checkbox', { name: /Pflaume/ })).not.toBeChecked()
      expect(screen.getByRole('checkbox', { name: /Apfel/ })).toBeChecked()
    })
  })
})
