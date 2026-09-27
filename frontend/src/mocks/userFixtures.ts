import type { UserInfo } from '../types/api'

export const mockUsers: UserInfo[] = [
  {
    id: 'mock-user-id',
    email: 'admin@opaa.local',
    displayName: 'Admin',
    systemRole: 'SYSTEM_ADMIN',
  },
  { id: 'owner-1', email: 'alice@opaa.local', displayName: 'Alice', systemRole: 'USER' },
  { id: 'owner-2', email: 'chris@opaa.local', displayName: 'Chris', systemRole: 'USER' },
  { id: 'curator-1', email: 'bob@opaa.local', displayName: 'Bob', systemRole: 'USER' },
  { id: 'demo-user', email: 'demo@opaa.local', displayName: 'Demo-Benutzer', systemRole: 'USER' },
]
