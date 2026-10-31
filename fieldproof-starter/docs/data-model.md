# Data model (initial proposal)

Use UUID primary keys. Store timestamps in UTC. Add `created_at` and `updated_at` where relevant. Server-managed records should carry an integer `version` for optimistic concurrency.

## Entities

### InspectionTemplate
- `id` UUID
- `name` string
- `version` integer
- `description` nullable string
- `is_active` boolean

### TemplateChecklistItem
- `id` UUID
- `template_id` UUID
- `label` string
- `required` boolean
- `evidence_requirement` enum: `none`, `photo`, `note`, `photo_or_note`
- `sort_order` integer

### Inspection
- `id` UUID (client-generated)
- `tenant_id` string (server-assigned from `X-Tenant-Id`; indexed. A placeholder until authentication provides a verified tenant — KL-001/KL-015)
- `template_id` UUID
- `template_version` integer
- `status` enum: `draft`, `in_progress`, `checklist_editable`, `submitted`, `changes_requested`, `approved`
- `version` integer (server version; local copy also tracks `base_server_version`)
- `created_at`, `updated_at` timestamps
- `created_by` UUID/string depending on auth decision

### ChecklistItem
- `id` UUID
- `inspection_id` UUID
- `template_item_id` UUID
- `status` enum: `not_started`, `pass`, `fail`, `not_applicable`
- `note` nullable string
- `version` integer (server version used as `expected_version` on outcome writes)

Local (Android Room) mirror adds: `completed` (kept for aggregate progress),
`syncedVersion` (last acknowledged server `version`; null until first sync) and
`syncState` (`local`, `queued`, `uploading`, `synced`, `failed`, `conflict`).

### Evidence
- `id` UUID (client-generated)
- `tenant_id` string (server-assigned; indexed)
- `inspection_id` UUID
- `checklist_item_id` UUID
- `kind` enum: `photo`, `note`
- `local_path` nullable string (device only; never sent as a server path)
- `storage_key` nullable string (server only; `tenants/{tenant_id}/inspections/{inspection_id}/evidence/{evidence_id}{ext}`)
- `content_type` nullable string (canonical type derived from magic bytes, not the client header)
- `sha256` nullable string
- `captured_at` timestamp
- `uploaded_at` nullable timestamp
- `sync_state` local enum: `queued`, `uploading`, `synced`, `failed`

### SyncOperation (local)
- `id` UUID; used as stable idempotency key
- `entity_type`, `entity_id`, `operation_type`
- `payload_version` integer
- `state` enum: `queued`, `in_progress`, `failed`, `succeeded`, `needs_resolution`
- `attempt_count` integer
- `last_error_code` nullable string
- `created_at`, `updated_at` timestamps

### Review
- `id` UUID
- `inspection_id` UUID
- `reviewer_id` UUID/string depending on auth decision
- `decision` enum: `approved`, `changes_requested`
- `comment` nullable string
- `inspection_version` integer reviewed
- `reviewed_at` timestamp

## Integrity rules

1. A report cannot be finalized unless every required checklist item has both its required evidence **and** a terminal outcome (`pass`/`fail`/`not_applicable`).
2. Evidence IDs and idempotency keys are unique server-side.
3. An update includes the expected server version; mismatches return a conflict response.
4. Approval references a specific inspection version.
5. Local file paths are private device metadata and are never accepted as server storage paths.
6. Records are namespaced per `tenant_id`; reads resolve the caller's tenant and cross-tenant access returns `404`. This is not yet an authorization boundary until authentication lands (KL-015).
7. Every photo's stored `content_type` is derived from its magic bytes and must be in the configured allowlist; uploads are size-bounded and scanned before persistence.
