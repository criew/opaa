import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import SkipLink from './SkipLink'
import { MAIN_CONTENT_ID } from './PageHeading'

describe('SkipLink', () => {
  afterEach(() => {
    document.getElementById(MAIN_CONTENT_ID)?.remove()
  })

  it('moves focus to the main content on activation', async () => {
    const main = document.createElement('main')
    main.id = MAIN_CONTENT_ID
    main.tabIndex = -1
    document.body.appendChild(main)
    const user = userEvent.setup()
    renderWithProviders(<SkipLink />)

    await user.click(screen.getByRole('link', { name: 'Zum Inhalt springen' }))

    expect(main).toHaveFocus()
  })
})
