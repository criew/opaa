# Cross-Workspace Document Sharing

> **Status: Early Draft — Significant open questions remain.**
> This feature was extracted from the Access Control & Workspaces spec because it requires a separate, in-depth design phase. The current concepts are not final and have known security gaps that must be resolved before implementation.

## Motivation

Users and teams need to make documents discoverable across workspace boundaries. For example, a team might want to share a design document with another team for review, or a user might want to make personal notes available to their team.

---

## Current Concept (Under Review)

### How Sharing Works

1. User selects a document in a workspace where they have Editor role
2. User selects "Share to workspace" and picks a target workspace
3. User must have at least Editor role in the target workspace
4. The document's indexed chunks gain the target workspace ID
5. Members of the target workspace can now find the document in search results
6. The original document remains in its home workspace (single source of truth)

Bulk sharing is supported — users can share multiple documents at once.

### Sharing Scenarios

```
Scenario 1: Personal → Team
  Alice shares "Notes.md" from "My Documents" → "Frontend-Team"
  Requirement: Alice is Editor in "Frontend-Team"

Scenario 2: Team → Team
  Alice shares "API-Spec.md" from "Backend-Team" → "Frontend-Team"
  Requirement: Alice is Editor in BOTH workspaces

Scenario 3: Team → Project
  Alice shares "Sprint-Results.md" from "Frontend-Team" → "Phoenix"
  Requirement: Alice is Editor in BOTH workspaces
```

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

### Sharing Notifications

When a document is shared to a workspace, members of the target workspace are notified.

### Revoking Shared Access

- Document owner can revoke sharing at any time
- Workspace Admin of the target can remove a shared document from their workspace
- When sharing is revoked, the document's chunks lose the workspace tag and are no longer returned in that workspace's search results
- Sharing actions (grant and revoke) are recorded in the audit log

### Shared Document Removal

Workspace Admins of the target workspace can remove incoming shared documents from their workspace. This removes the workspace tags from the document's chunks — the original document in the source workspace is unaffected.

### External Sharing (Share Links)

Limited external access via share links:

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

## Known Security Concerns

The current "Editor in both workspaces" model has a **fundamental security gap**:

### Problem: Unintended Information Disclosure

Consider two workspaces:
- **Workspace A:** "Confidential Manager Documents" (members: managers only)
- **Workspace B:** "Employee FAQs" (members: all employees)

If a manager is Editor in both workspaces, they could share a confidential salary document from Workspace A to Workspace B. All employees in Workspace B would then see this document in their search results — a clear security violation.

The current model assumes that having Editor role in the source workspace implies the right to distribute its contents. This is not necessarily true. **Being allowed to edit documents does not mean being allowed to declassify them.**

### What Needs to Be Resolved

Before implementing sharing, the following questions must be answered:

1. **Permission check in target workspace:** Should there be a verification that the shared document's sensitivity level is compatible with the target workspace's audience? If so, how is sensitivity determined?
2. **Approval workflow:** Should sharing require explicit approval by an Admin of the target workspace? This would prevent unilateral sharing but adds friction.
3. **Role-based sharing restrictions:** Is "Editor in both" the right permission requirement? Perhaps sharing should require Admin role in the source workspace (higher authority to distribute), or a new "Share" permission separate from "Edit".
4. **Visibility rules for shared documents:** When a document is shared into a workspace, does it become visible to ALL members of the target workspace, or only to members with a specific role?
5. **Classification-based controls:** Should documents have a classification level (e.g., Public, Internal, Confidential, Restricted) that limits which workspaces they can be shared to?

---

## Open Questions

- **User-to-User sharing:** Direct sharing between personal workspaces is currently not possible (sharing requires Editor role in both workspaces, and personal workspaces don't allow other members). Possible solutions: (a) share via a common workspace, (b) introduce a user-level sharing mechanism (e.g., "Share document with User X"), or (c) accept the common-workspace workaround as a deliberate design choice.
- Should shared documents support **read-only vs. editable** sharing?
- Should there be a **limit** on how many workspaces a document can be shared to?
- Should workspace admins be able to **"request"** documents from users' personal workspaces?
- How does sharing interact with **connector permissions from source systems** (e.g., Confluence space permissions)?
- Should there be a **sharing audit dashboard** showing all active shares across the organization?

---

## Integration Points

- **Access Control & Workspaces:** Sharing extends workspace-level permissions — see [Access Control & Workspaces](./access-control-workspaces.md)
- **Data Indexing & RAG:** Shared documents gain additional `workspace_ids` tags on their chunks
- **User Frontends:** Sharing UI, share management, notifications
- **Audit & Compliance:** All sharing actions logged
