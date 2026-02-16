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
2. **Roles** — Job functions with specific permissions
3. **Document Permissions** — Fine-grained control over individual documents
4. **Query-Time Enforcement** — Permissions checked when user searches

---

## Workspaces

### Concept

A **workspace** is a self-contained area of OPAA:
- Has its own documents, users, and roles
- Has its own settings and configurations
- Users in one workspace don't see documents from another
- Each workspace can have its own indexing schedule

### Workspace Examples

| Workspace | Members | Documents | Visibility |
|-----------|---------|-----------|------------|
| Engineering | Developers, Architects | Code docs, ADRs, Design docs | Only engineers |
| Marketing | Marketing team | Brand guidelines, Campaign plans | Only marketing |
| HR | HR staff | Policies, Handbooks | Only HR (sensitive) |
| Company | All employees | Public policies, All-hands notes | Everyone |

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

---

## Roles & Permissions

### Built-in Roles

#### Viewer
Read-only access. Can:
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
Can modify documents. Can:
- Everything viewers can do
- Upload new documents
- Edit document metadata
- Add/remove documents
- Change document permissions

Cannot:
- Manage users
- Change workspace settings
- Delete workspace

#### Admin
Full workspace control. Can:
- Everything editors can do
- Manage users and roles
- Change workspace settings
- Configure indexing
- Manage integrations
- View audit logs

#### Owner
Only one per workspace. Can:
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
- **Public Workspace:** Everyone included (policies, all-hands notes)
- **Team Workspaces:** Isolated by team (engineering, marketing, hr)
- **Special Workspaces:** Cross-functional (executive, board)

User access example:
```
Employee: Sarah Chen
  Workspaces: [Company, Engineering, Executive]
  Roles: [viewer in Company, editor in Engineering, viewer in Executive]
```

Cross-team search:
- Sarah can search all three workspaces simultaneously
- Results filtered by her role in each workspace
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

---

## Success Metrics

- **Adoption:** % of users without "owner" role (healthy distribution)
- **Compliance:** 100% of audit logs retained and accessible
- **Performance:** Permission check adds < 50ms to query time
- **Accuracy:** 0 unintended access incidents
- **Usability:** New users understand workspace/role model in < 5 minutes
