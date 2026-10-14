import { describe, expect, it } from 'vitest'
import { userInitial } from './userInitial'

// #800 (review #795, finding 5): `?? `-chains let a blank displayName through, and
// `''[0].toUpperCase()` throws - the helper trims before indexing.
describe('userInitial', () => {
  it('prefers the display name and upper-cases it', () => {
    expect(userInitial({ displayName: 'birgit', email: 'x@y.de' })).toBe('B')
  })

  it('falls back to the e-mail address', () => {
    expect(userInitial({ displayName: null, email: 'wagner@y.de' })).toBe('W')
  })

  it('survives a blank display name instead of throwing', () => {
    expect(userInitial({ displayName: '   ', email: 'x@y.de' })).toBe('X')
  })

  it('keeps a non-BMP first character whole instead of splitting the surrogate pair (#805)', () => {
    expect(userInitial({ displayName: '🦊 Fuchs', email: null })).toBe('🦊')
  })

  it('renders a placeholder without any source', () => {
    expect(userInitial(null)).toBe('?')
    expect(userInitial({ displayName: '', email: '' })).toBe('?')
  })
})
