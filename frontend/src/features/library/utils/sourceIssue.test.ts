import { describe, expect, it } from 'vitest'
import { confluenceIssue, EMPTY_CONFLUENCE_VALUES } from './confluenceSource'
import { dropboxIssue, EMPTY_DROPBOX_VALUES } from './dropboxSource'
import { EMPTY_FILESYSTEM_VALUES, filesystemIssue } from './filesystemSource'
import { EMPTY_GOOGLE_DRIVE_VALUES, googleDriveIssue } from './googleDriveSource'
import { EMPTY_NEXTCLOUD_VALUES, nextcloudIssue } from './nextcloudSource'
import { EMPTY_S3_VALUES, s3ValuesIssue } from './s3Source'
import { EMPTY_SMB_VALUES, smbIssue } from './smbSource'

const fieldOf = (issue: { field?: string } | null) => issue?.field

// The field of an issue is the form value the source form shows it at.
describe('the field a source issue names', () => {
  it('names the path and the patterns of a FILESYSTEM source', () => {
    expect(fieldOf(filesystemIssue(EMPTY_FILESYSTEM_VALUES))).toBe('sourcePath')
    expect(
      fieldOf(
        filesystemIssue({ ...EMPTY_FILESYSTEM_VALUES, sourcePath: '/data', excludePatterns: '/x' }),
      ),
    ).toBe('excludePatterns')
  })

  it('names the S3 field of each stage, a scope by its row', () => {
    const complete = {
      ...EMPTY_S3_VALUES,
      allBuckets: false,
      sourceUrl: 'https://minio.example:9000',
      accessKey: 'AKIA',
      secretKey: 'geheim',
      scopes: [{ bucket: 'protokolle', prefix: '' }],
    }
    expect(s3ValuesIssue(complete)).toBeNull()
    expect(fieldOf(s3ValuesIssue({ ...complete, sourceUrl: 'minio' }))).toBe('sourceUrl')
    expect(fieldOf(s3ValuesIssue({ ...complete, region: 'eu central' }))).toBe('region')
    expect(fieldOf(s3ValuesIssue({ ...complete, accessKey: '' }))).toBe('accessKey')
    expect(fieldOf(s3ValuesIssue({ ...complete, secretKey: 'a:b' }))).toBe('secretKey')
    expect(fieldOf(s3ValuesIssue({ ...complete, sessionToken: 'a:b' }))).toBe('sessionToken')
    expect(
      fieldOf(
        s3ValuesIssue({
          ...complete,
          scopes: [
            { bucket: 'protokolle', prefix: '' },
            { bucket: '', prefix: '' },
            { bucket: 'Ungültig', prefix: '' },
          ],
        }),
      ),
    ).toBe('scopes.2.bucket')
    expect(
      fieldOf(s3ValuesIssue({ ...complete, scopes: [{ bucket: 'protokolle', prefix: 'a/../b' }] })),
    ).toBe('scopes.0.prefix')
    expect(fieldOf(s3ValuesIssue({ ...complete, excludePatterns: 'a\na' }))).toBe('excludePatterns')
  })

  it('names the address of Confluence, and no field for the later stages', () => {
    expect(fieldOf(confluenceIssue(EMPTY_CONFLUENCE_VALUES))).toBe('sourceUrl')
    expect(
      confluenceIssue({ ...EMPTY_CONFLUENCE_VALUES, sourceUrl: 'https://wiki.example' }),
    ).toEqual({ message: expect.stringMatching(/Edition erkennen/) })
  })

  it('names address, the missing half of the credentials and the folders of Nextcloud', () => {
    const base = { ...EMPTY_NEXTCLOUD_VALUES, sourceUrl: 'https://cloud.example' }
    expect(fieldOf(nextcloudIssue(EMPTY_NEXTCLOUD_VALUES, false))).toBe('sourceUrl')
    expect(fieldOf(nextcloudIssue({ ...base, username: 'svc' }, false))).toBe('appPassword')
    expect(fieldOf(nextcloudIssue({ ...base, appPassword: 'x' }, false))).toBe('username')
    expect(fieldOf(nextcloudIssue({ ...base, folders: '' }, true))).toBe('folders')
  })

  it('names address, the missing half of the credentials and the folders of SMB', () => {
    const base = { ...EMPTY_SMB_VALUES, sourceUrl: 'smb://server/freigabe' }
    expect(fieldOf(smbIssue({ ...EMPTY_SMB_VALUES, sourceUrl: 'smb://server' }, false))).toBe(
      'sourceUrl',
    )
    expect(fieldOf(smbIssue({ ...base, account: 'svc' }, false))).toBe('password')
    expect(fieldOf(smbIssue({ ...base, folders: '/a,b' }, true))).toBe('folders')
  })

  it('names the folders of Dropbox and the imitated account of Google Drive', () => {
    expect(fieldOf(dropboxIssue({ ...EMPTY_DROPBOX_VALUES, folders: '' }))).toBe('folders')
    expect(
      fieldOf(
        googleDriveIssue(
          {
            ...EMPTY_GOOGLE_DRIVE_VALUES,
            keyFile: '{}',
            scopes: [{ kind: 'drive', id: 'd1', name: 'Team' }],
            subject: 'kein-konto',
          },
          false,
        ),
      ),
    ).toBe('subject')
    expect(googleDriveIssue(EMPTY_GOOGLE_DRIVE_VALUES, false)?.field).toBeUndefined()
  })
})
