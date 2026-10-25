import { describe, expect, it } from 'vitest'
import type { SourceTypeDescriptor } from '@/shared/api/types'
import { mockSourceTypes } from '../mocks/libraryFixtures'
import {
  OWN_ADDRESS,
  effectiveConnection,
  onlyPersons,
  privateConnection,
} from './connectionChoice'

function descriptorOf(type: string): SourceTypeDescriptor {
  const descriptor = mockSourceTypes.find((d) => d.type === type)
  if (!descriptor) throw new Error(`no descriptor for ${type}`)
  return descriptor
}

describe('connectionChoice (#3152)', () => {
  describe('onlyPersons', () => {
    it('holds for a type whose every sign-in admits only persons', () => {
      expect(onlyPersons(descriptorOf('ONEDRIVE'))).toBe(true)
    })

    it('does not hold for a type that also admits libraries, nor for one without sign-ins', () => {
      expect(onlyPersons(descriptorOf('SHAREPOINT'))).toBe(false)
      expect(
        onlyPersons({
          ...descriptorOf('NEXTCLOUD'),
          signIns: [
            { method: 'PERSONAL_SECRET', ownerships: ['LIBRARY', 'PERSON'], secretForm: 'TOKEN' },
          ],
        }),
      ).toBe(false)
      expect(onlyPersons({ ...descriptorOf('ONEDRIVE'), signIns: [] })).toBe(false)
    })
  })

  describe('effectiveConnection', () => {
    const own = privateConnection('profile-a')

    it('takes the one private way of a type only for persons', () => {
      expect(effectiveConnection(null, [own], true)).toBe(own)
    })

    it('never takes a private way by itself where there are several', () => {
      expect(effectiveConnection(null, [own, privateConnection('profile-b')], true)).toBeNull()
    })

    it('leaves a single private way of a mixed type to an explicit choice', () => {
      expect(effectiveConnection(null, [own])).toBeNull()
      expect(effectiveConnection(null, [own], false)).toBeNull()
    })

    it('keeps a choice still selectable and falls back to the first shared way', () => {
      expect(effectiveConnection(own, ['profile-a', OWN_ADDRESS, own])).toBe(own)
      expect(effectiveConnection('gone', ['profile-a', OWN_ADDRESS, own])).toBe('profile-a')
    })
  })
})
