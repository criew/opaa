# Access Control & Workspaces

## Motivation

Not all organizational knowledge is meant for everyone. A company has:
- Public policies everyone can access
- Team-specific documentation only relevant to teams
- Sensitive information (salary info, proprietary data) restricted to specific roles
- Compliance documents only for auditors

This feature describes how OPAA controls who can see what, enabling multi-team, multi-role access with fine-grained permissions.

---

## Overview

OPAA provides access control at multiple levels:

1. **Workspaces** — Logical groupings of documents and users (teams, departments)
2. **Personal Workspaces** — Auto-created private spaces for individual user documents
3. **Roles** — Job functions with specific permissions
4. **Document Permissions** — Fine-grained control over individual documents
5. **Query-Time Enforcement** — Permissions checked when user searches

---

## Workspaces

### Concept

A **workspace** is a self-contained area of OPAA:
- Has its own documents, users, and roles
- Has its own settings and configurations
- Users in one workspace don't see documents from another
- Each workspace can have its own indexing schedule

A special type of workspace, the **Personal Workspace** ("My Documents"), is automatically created for each user. It functions identically to a regular workspace but is owned and visible exclusively to a single user. Users cannot be added as members of another user's personal workspace. See [Personal Workspaces](#personal-workspaces) below.

### Workspace Examples

| Workspace | Members | Documents | Visibility |
|-----------|---------|-----------|------------|
| Engineering | Developers, Architects | Code docs, ADRs, Design docs | Only engineers |
| Marketing | Marketing team | Brand guidelines, Campaign plans | Only marketing |
| HR | HR staff | Policies, Handbooks | Only HR (sensitive) |
| Company | All employees | Public policies, All-hands notes | Everyone |
| My Documents (Sarah) | Sarah only | Uploaded research, notes, drafts | Only Sarah |

### Workspace Management

#### Creating Workspaces

Admin workflow:

```
1. Choose workspace name: "Engineering"
2. Set workspace owner: Sarah Chen
3. Add initial members: Select from user directory
4. Set description: "For engineering team documentation"
5. Configure defaults:
   - Default role for new members: "viewer"
   - Indexing schedule: Daily 2 AM
   - Retention policy: Keep 2 years
6. Save
```

#### Managing Workspace Members

Add/remove users:
```
Workspace: Engineering

Members:
  Sarah Chen (owner)      → Can manage members, change settings
  Alex Johnson (editor)   → Can add documents, change permissions
  Jamie Lee (viewer)      → Can read, search, download
  Pat Miller (denied)     → Cannot access
```

#### Workspace Isolation

Default behavior: **Complete isolation**
- Users can only query documents in their workspaces
- Cannot see other workspaces in UI
- Search results only include their workspace's docs
- API tokens inherit workspace access

Optional: **Shared workspaces** (for cross-team needs)
- Multiple teams added to single workspace
- Role-based permissions within shared workspace
- Audit logging tracks who searched what

### Personal Workspaces

#### Auto-Creation

When a user first logs in or uploads their first document, OPAA automatically creates a personal workspace:

```
Workspace: "My Documents"
  Type: personal
  Owner: [user]
  Members: [user] (cannot be changed)
  Visibility: private (only the owner)
  Auto-created: true
  Deletable: no (exists as long as user account exists)
```

#### Characteristics

- One per user, cannot be duplicated
- User is always Owner with full control
- Cannot invite other members directly
- Documents are shared OUT of the personal workspace into team workspaces (not the reverse)
- Has its own indexing scope for RAG queries
- Included in cross-workspace search results (for the owning user only)

#### Storage

Personal workspace documents are stored on the deployment's configured storage backend (S3, network drive, or local filesystem). The storage location is transparent to the user. See [Data Indexing & RAG — User Document Upload](./data-indexing-rag.md#user-document-upload) for details.

---

## Cross-Workspace Document Sharing

### Concept

Users can share documents from their personal workspace into team workspaces where they have Editor (or higher) role. This makes personal documents discoverable and searchable by other workspace members.

### How Sharing Works

1. User has a document in "My Documents"
2. User selects "Share to workspace" and picks a target workspace (e.g., "Engineering")
3. User must have at least Editor role in the target workspace
4. The document's indexed chunks gain the target workspace ID
5. Members of "Engineering" can now find the document in search results
6. The original document remains in the user's personal workspace

### Sharing Model

```
Document: "Q1 Design Review"
  Owner: Sarah Chen
  Home workspace: My Documents (Sarah)
  Shared to: [Engineering, Architecture]

  Visibility:
    - Sarah: always (owner)
    - Engineering members: yes (shared)
    - Architecture members: yes (shared)
    - Marketing members: no (not shared)
```

### Sharing vs. Moving

- **Share:** Document visible in multiple workspaces. Single source of truth. Owner retains control.
- **Move:** (Not supported) Documents always have a home workspace. If a user wants to permanently place a document in a team workspace, they upload directly to that workspace (requires Editor role).

### Revoking Shared Access

- Document owner can revoke sharing at any time
- Workspace Admin can remove a shared document from their workspace
- When sharing is revoked, the document's chunks lose the workspace tag and are no longer returned in that workspace's search results

### Upload Directly to Team Workspace

Users with Editor role in a team workspace can also upload documents directly to that workspace (bypassing the personal workspace). In this case:
- The document's home workspace is the team workspace
- The uploading user is the document owner
- Standard workspace permissions apply

---

## Roles & Permissions

### Built-in Roles

#### Viewer
Read-only access.

Can:
- Ask questions (OPAA searches documents)
- Download documents
- View conversation history
- Rate answers

Cannot:
- Add/modify documents
- Change permissions
- Manage users
- View other workspaces

#### Editor
Can modify documents.

Can:
- Everything viewers can do
- Upload new documents
- Edit document metadata
- Add/remove documents
- Change document permissions
- Share personal documents into the workspace

Cannot:
- Manage users
- Change workspace settings
- Delete workspace

#### Admin
Full workspace control.

Can:
- Everything editors can do
- Manage users and roles
- Change workspace settings
- Configure indexing
- Manage integrations
- View audit logs

#### Owner
Only one per workspace.

Can:
- Transfer workspace ownership
- Delete workspace
- All admin permissions

### Custom Roles

Organizations can create custom roles:

```yaml
CustomRole:
  name: "Research Lead"
  inherits_from: "Editor"
  permissions:
    - read_documents
    - create_documents
    - edit_documents_own  # Only their own
    - manage_indexing
    - view_analytics
  restrictions:
    - cannot_delete_published
    - cannot_access_sensitive_tag
```

### Permission Matrix

| Action | Viewer | Editor | Admin | Owner |
|--------|--------|--------|-------|-------|
| Search documents | ✅ | ✅ | ✅ | ✅ |
| Download documents | ✅ | ✅ | ✅ | ✅ |
| View sources | ✅ | ✅ | ✅ | ✅ |
| Add documents | ❌ | ✅ | ✅ | ✅ |
| Edit documents | ❌ | ✅* | ✅ | ✅ |
| Upload documents | ❌ | ✅ | ✅ | ✅ |
| Share documents into workspace | ❌ | ✅ | ✅ | ✅ |
| Delete documents | ❌ | ❌ | ✅ | ✅ |
| Change permissions | ❌ | ❌ | ✅ | ✅ |
| Manage users | ❌ | ❌ | ✅ | ✅ |
| Transfer ownership | ❌ | ❌ | ❌ | ✅ |
| Delete workspace | ❌ | ❌ | ❌ | ✅ |

*Editor can edit their own documents if permission set

---

## Document-Level Permissions

### Permission Granularity

Beyond roles, individual documents can have:
- **Owner** — User who added/owns the document
- **Readers** — Users/roles who can view
- **Editors** — Users/roles who can modify
- **Tags** — Metadata for grouping permissions

### Permission Models

#### Model 1: Inherit from Source
Confluence documents inherit Confluence space permissions:
- Only users with wiki access see wiki documents
- Automatically updated as wiki permissions change
- Transparent to OPAA users

#### Model 2: Explicit OPAA Permissions
Fine-grained control within OPAA:

```
Document: "Salary Review Process"
  Owner: HR Manager
  Readers: [role:hr_team, role:managers]
  Editors: [HR Manager]
  Tags: [sensitive, restricted]
```

#### Model 3: Tag-Based
Bulk permissions via tags:

```
Tag: "public"         → Readers: all_authenticated_users
Tag: "team_only"      → Readers: [current_workspace_members]
Tag: "managers"       → Readers: [role:manager, role:director]
Tag: "sensitive"      → Readers: [role:hr, role:compliance]
Tag: "public_website" → Readers: [anonymous, authenticated]
```

### Sharing Documents Externally

Limited external access:

```
Create share link with:
  - Expiry date (e.g., 7 days)
  - Read-only access
  - Optional password
  - Tracking enabled (see who accessed)

Link: https://opaa.company.com/share/abc123xyz
  - Valid until Feb 23, 2024
  - Can be revoked any time
  - Access logged in audit trail
```

---

## Query-Time Permission Enforcement

### How It Works

When user searches:

1. **Query:** "What's our HR policy?"
2. **RAG Retrieval:** System finds 10 relevant documents
3. **Permission Filter:** For each document:
   - Check if user has "read" permission
   - Check if document is in user's workspace
   - Check document tags and access rules
4. **Return:** Only documents user can access (e.g., 3 of 10)
5. **Response:** "Based on HR policies you have access to..."

**Key principle:** User never knows documents exist that they can't access. Results look complete but are filtered.

### Permission Caching

For performance:
- User permissions cached (10 minute TTL)
- Document permissions cached (1 hour TTL)
- Permissions revocation flushes cache immediately
- Admin actions clear cache

---

## User Management

### User Identity

Users can authenticate via:
- **Single Sign-On (SSO)** — OIDC, SAML (recommended)
- **Local Accounts** — Username/password (fallback only)
- **API Tokens** — For programmatic access

**Recommended:** SSO integration with Active Directory/Okta

### User Directory Sync

OPAA can sync with directory:

```
Sync Frequency: Every 6 hours

From Active Directory:
  - User names and emails
  - Group memberships
  - Department
  - Job title
  - Manager

Auto-mapping:
  - AD Group "engineering" → opaa-workspace: Engineering
  - AD Group "hr" → opaa-workspace: HR
  - AD attribute "department" → workspace assignment
```

When user leaves organization:
- Directory sync removes them
- Their documents remain (owned by them)
- No longer can log in
- Option to transfer document ownership

### API Tokens & Service Accounts

For integrations:

```
Create API Token:
  Name: "Slack Bot"
  Workspace: Engineering
  Scope: [read_documents, ask_questions]
  Rotation: 90 days
  IP whitelist: 10.0.1.0/24 (optional)
  Rate limit: 1000 requests/day

Token: opaa_token_abc123xyz_def456uvw
```

Service accounts:
- No interactive login
- Pure API access
- No user interface
- Used for bots, integrations, scripts

---

## Audit & Compliance

### Audit Logging

Every action logged:

```json
{
  "timestamp": "2024-02-16T14:30:15Z",
  "user_id": "user-123",
  "action": "search",
  "workspace": "engineering",
  "query": "system architecture",  // Not logged by default
  "results_count": 5,
  "documents_accessed": ["doc-1", "doc-2", "doc-3"],
  "result": "success",
  "ip_address": "10.0.1.45",
  "user_agent": "Chrome/120.0"
}
```

Logs retained:
- Minimum: 1 year (configurable)
- Can be exported to SIEM (Splunk, ELK, etc.)
- Cannot be deleted (immutable append-only)

### Compliance Reports

Generate reports:
- **User Access Report:** Who accessed what and when
- **Permission Changes:** Who changed permissions
- **Sensitive Document Access:** Who viewed restricted docs
- **Failed Access Attempts:** Permission denials

Used for:
- SOC 2 audit trail
- HIPAA compliance
- GDPR data access requests
- Internal investigations

### Data Deletion (GDPR Right to be Forgotten)

When user account deleted:

```
1. Remove user from all workspaces
2. Transfer ownership of their documents (optional)
3. Delete user account and auth tokens
4. Keep audit logs (for compliance) but redact user info
5. Anonymize personal data
```

Document deletion:
- Can only be done by workspace admin
- Creates audit entry (when, who, why)
- Option for permanent deletion (after retention period)

---

## Workspace Strategies

### Strategy 1: One Workspace Per Team
Each team has isolated workspace:
- **Pros:** Simple, clear isolation, team ownership
- **Cons:** No cross-team search, data duplication
- **Best for:** Organizations with siloed teams

### Strategy 2: Single Company Workspace
All documents in one workspace, role-based permissions:
- **Pros:** Cross-team search, unified knowledge
- **Cons:** Complex permission management, one admin for all
- **Best for:** Smaller companies with good cross-team collaboration

### Strategy 3: Hybrid (Recommended)
Multiple workspaces + cross-team searches:
- **Personal Workspaces:** Every user has "My Documents" (private, auto-created)
- **Public Workspace:** Everyone included (policies, all-hands notes)
- **Team Workspaces:** Isolated by team (engineering, marketing, hr)
- **Special Workspaces:** Cross-functional (executive, board)

User access example:
```
Employee: Sarah Chen
  Workspaces: [My Documents, Company, Engineering, Executive]
  Roles: [owner in My Documents, viewer in Company, editor in Engineering, viewer in Executive]
```

Cross-team search:
- Sarah can search all four workspaces simultaneously
- Results filtered by her role in each workspace
- Documents she uploaded privately appear alongside team and company documents
- Workspace name shown in results

---

## Special Cases

### Executive Access

Executives need broad access:

```
Role: Executive
Inherits: Admin
Workspaces: [Company, All]
Permissions:
  - Search all workspaces simultaneously
  - Generate cross-team reports
  - View usage analytics for entire company
```

### Audit & Compliance Teams

Compliance staff need access for audits:

```
Role: Auditor
Workspaces: [All] (read-only)
Permissions:
  - Search all workspaces
  - View audit logs
  - Generate compliance reports
  - Cannot modify anything
```

### External Consultants

Limited time, limited access:

```
User: External Consultant
Workspaces: [Project-X]
Role: viewer (temporary)
Expiry: 2024-03-31
Restrictions:
  - Can only view documents tagged "consultant-access"
  - No API token access
  - Downloads logged
```

### User Offboarding with Personal Workspace

When a user leaves the organization, their personal workspace requires special handling:

```
1. Personal workspace documents shared to team workspaces remain accessible
   (ownership transfers to workspace admin)
2. Unshared personal documents can be:
   - Transferred to another user or workspace
   - Archived
   - Deleted (after retention period)
3. Personal workspace is deactivated (not deleted, for audit purposes)
4. All sharing relationships from the personal workspace are preserved
   under the new owner
```

---

## Integration Points

- **Authentication:** Integrates with SSO provider
- **User Frontends:** Enforce permissions at each interface
- **Data Indexing:** Respect source permissions (Confluence, etc.)
- **RAG Engine:** Filter results by user permissions
- **Deployment Infrastructure:** User/group data from directory

---

## Open Questions / Future Enhancements

- Should we support attribute-based access control (ABAC)?
- Should we support time-based permissions (access only 9-5)?
- Should we support geo-fencing (IP restrictions)?
- Should we support approval workflows for sensitive documents?
- Should we support delegation (user A delegates their permissions to B)?
- Should we support document classification (public/internal/confidential)?
- Should shared documents support read-only vs. editable sharing?
- Should there be a limit on how many workspaces a document can be shared to?
- Should workspace admins be able to "request" documents from users' personal workspaces?

---

## Success Metrics

- **Adoption:** % of users without "owner" role (healthy distribution)
- **Compliance:** 100% of audit logs retained and accessible
- **Performance:** Permission check adds < 50ms to query time
- **Accuracy:** 0 unintended access incidents
- **Usability:** New users understand workspace/role model in < 5 minutes
