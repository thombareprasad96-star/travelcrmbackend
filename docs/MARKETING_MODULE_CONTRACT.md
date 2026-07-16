# Marketing & Campaigns — API Contract (LOCKED)

This is the single source of truth for the Marketing & Campaigns module. The frontend
was built first against this contract (with realistic mock data behind it); the backend
is built strictly to satisfy it. **Do not drift.** Any change here must be applied on both
sides in the same commit.

- **Base path:** `/api/marketing`
- **Envelopes:** every single-object endpoint returns `ApiResponse<T>`
  (`{ success, message, data, statusCode, timestamp }`); every list endpoint returns
  `PagedApiResponse<T>` (`{ success, message, data: [...], pagination, timestamp }`).
  Errors use the standard `ApiError` shape (`{ success:false, status, code, message, fieldErrors, details, traceId, timestamp }`).
- **`pagination` block** (exactly as `PaginationMeta`):
  `{ page (0-based), size, totalElements, totalPages, first, last, hasNext, hasPrevious, sortBy, sortDir }`.
- **IDs:** only `publicId` (UUID) is ever exposed. Internal `Long id` never leaves the server.
- **Enums on the wire:**
  - Marketing-owned enums (channel/status/audienceType/operator/…) serialize as their **UPPERCASE name**.
  - Customer-derived enum *values* inside segment conditions also use the enum **name**
    (`"GOLD"`, `"VIP"`, `"WHATSAPP"`); the field catalog carries `{value, label}` so the UI shows
    the display label (`"Gold"`) while storing the name.
- **Auth:** JWT (staff realm). Permissions below.
- **Permissions (added to `Permission` enum, module `"Marketing"`):**
  `MARKETING_READ`, `MARKETING_CREATE`, `MARKETING_UPDATE`, `MARKETING_DELETE`, `MARKETING_SEND`.
  Defaults: `TENANT_ADMIN` all; `MANAGER` all five; `TRAVEL_AGENT` read-only; others none.
  Row scope: Segment/Campaign/DripSequence are `Ownable` and pass through `SubAgentScope`
  (no-op for admins/managers → they see the whole tenant; sub-agents have no marketing access anyway).

---

## 1. Enums

```
MarketingChannel     : WHATSAPP | EMAIL
CampaignStatus       : DRAFT | SCHEDULED | SENDING | SENT | FAILED | CANCELLED
CampaignAudienceType : SEGMENT | ALL_CUSTOMERS
RecipientStatus      : PENDING | SENT | FAILED | SKIPPED
SegmentMatchType     : ALL | ANY
ConditionOperator    : EQUALS | NOT_EQUALS | IN | NOT_IN | CONTAINS | GREATER_THAN | LESS_THAN | BETWEEN | IS_SET | IS_NOT_SET
SegmentFieldType     : ENUM | TEXT | DATE | NUMBER | BOOLEAN | MONTH
DripStatus           : DRAFT | ACTIVE | PAUSED
DripAudienceType     : SEGMENT | MANUAL
EnrollmentStatus     : ACTIVE | COMPLETED | CANCELLED
AutomationType       : BIRTHDAY | ANNIVERSARY
```

---

## 2. Segments

Query-builder over the tenant's `customers`. Rules are stored as JSON on the segment; the
backend evaluates them as a tenant-scoped JPA Specification (always AND `tenant_id` + `deleted_at IS NULL`).

### Field catalog — `GET /api/marketing/segments/fields` → `ApiResponse<List<SegmentFieldDef>>`
```jsonc
{ "field":"loyaltyTier", "label":"Loyalty Tier", "type":"ENUM",
  "operators":["IN","NOT_IN","EQUALS","NOT_EQUALS"],
  "options":[{"value":"BRONZE","label":"Bronze"},{"value":"SILVER","label":"Silver"},
             {"value":"GOLD","label":"Gold"},{"value":"PLATINUM","label":"Platinum"}] }
```
Fields shipped: `loyaltyTier` (ENUM), `customerType` (ENUM), `status` (ENUM), `commPref` (ENUM),
`city` (TEXT), `state` (TEXT), `email` (TEXT: IS_SET/IS_NOT_SET/CONTAINS),
`birthdayMonth` (MONTH 1-12), `anniversaryMonth` (MONTH), `createdAt` (DATE: BETWEEN/GREATER_THAN/LESS_THAN).
`options` present only for ENUM and MONTH types.

### Condition shape (shared by request / response / preview)
```jsonc
{ "field":"loyaltyTier", "operator":"IN", "value":["GOLD","PLATINUM"] }
// value: string (EQUALS/CONTAINS), string[] (IN/NOT_IN), number (MONTH),
//        "YYYY-MM-DD" (DATE cmp), ["YYYY-MM-DD","YYYY-MM-DD"] (BETWEEN); absent for IS_SET/IS_NOT_SET
```

### CRUD
| Method | Path | Auth | Body / Result |
|---|---|---|---|
| GET | `/api/marketing/segments?page&size&sortBy&sortDir&search` | READ | `PagedApiResponse<SegmentResponse>` |
| GET | `/api/marketing/segments/{publicId}` | READ | `ApiResponse<SegmentResponse>` (recomputes `memberCount`) |
| POST | `/api/marketing/segments` | CREATE | `CreateSegmentRequest` → `ApiResponse<SegmentResponse>` (201) |
| PUT | `/api/marketing/segments/{publicId}` | UPDATE | `UpdateSegmentRequest` → `ApiResponse<SegmentResponse>` |
| DELETE | `/api/marketing/segments/{publicId}` | DELETE | `ApiResponse<Void>` (soft delete) |
| POST | `/api/marketing/segments/preview` | READ | `SegmentPreviewRequest` → `ApiResponse<SegmentPreviewResponse>` |
| GET | `/api/marketing/segments/{publicId}/members?page&size` | READ | `PagedApiResponse<SegmentMemberResponse>` |

```jsonc
// CreateSegmentRequest / UpdateSegmentRequest
{ "name":"High-value Gold+", "description":"…",
  "matchType":"ALL", "conditions":[ {field,operator,value}, … ] }

// SegmentResponse
{ "publicId":"…","name":"…","description":"…","matchType":"ALL",
  "conditions":[…], "memberCount":128, "ownerName":"Priya",
  "createdAt":"2026-07-15T10:00:00","updatedAt":"2026-07-15T10:00:00" }

// SegmentPreviewRequest  { "matchType":"ANY", "conditions":[…] }
// SegmentPreviewResponse
{ "totalCount":128,
  "sample":[ {"publicId":"…","name":"…","email":"…","phone":"…","city":"…","loyaltyTier":"GOLD"}, … ] } // ≤ 25

// SegmentMemberResponse
{ "publicId","name","email","phone","city","state","loyaltyTier","customerType","status" }
```

---

## 3. Campaigns (bulk broadcast)

| Method | Path | Auth | Body / Result |
|---|---|---|---|
| GET | `/api/marketing/campaigns?page&size&sortBy&sortDir&search&status&channel` | READ | `PagedApiResponse<CampaignResponse>` |
| GET | `/api/marketing/campaigns/summary` | READ | `ApiResponse<CampaignSummaryResponse>` |
| GET | `/api/marketing/campaigns/{publicId}` | READ | `ApiResponse<CampaignResponse>` |
| POST | `/api/marketing/campaigns` | CREATE | `CreateCampaignRequest` → `ApiResponse<CampaignResponse>` (201) |
| PUT | `/api/marketing/campaigns/{publicId}` | UPDATE | `UpdateCampaignRequest` (only while DRAFT/SCHEDULED) |
| DELETE | `/api/marketing/campaigns/{publicId}` | DELETE | `ApiResponse<Void>` |
| POST | `/api/marketing/campaigns/{publicId}/send` | SEND | schedule-now / dispatch → `ApiResponse<CampaignResponse>` |
| POST | `/api/marketing/campaigns/{publicId}/cancel` | SEND | cancel a scheduled campaign → `ApiResponse<CampaignResponse>` |
| POST | `/api/marketing/campaigns/{publicId}/test` | SEND | `SendTestRequest {to}` → `ApiResponse<Void>` |
| GET | `/api/marketing/campaigns/{publicId}/recipients?page&size&status` | READ | `PagedApiResponse<CampaignRecipientResponse>` |

```jsonc
// CreateCampaignRequest / UpdateCampaignRequest
{ "name":"Diwali Offer", "channel":"WHATSAPP",
  "audienceType":"SEGMENT", "segmentPublicId":"…",   // required iff SEGMENT
  "subject":"…",                                      // EMAIL only
  "body":"Hi {{name}}, …",                            // required; supports merge tags
  "templateName":null,                                // WHATSAPP optional override (else tenant default)
  "scheduledAt":"2026-07-20T09:00:00" }               // optional; null = draft (or immediate on /send)

// CampaignResponse
{ "publicId","name","channel","status","audienceType",
  "segmentPublicId","segmentName","subject","body","templateName",
  "scheduledAt","sentAt","totalRecipients","sentCount","failedCount",
  "ownerName","createdAt","updatedAt" }

// CampaignSummaryResponse
{ "total":42,"drafts":6,"scheduled":3,"sent":33,
  "messagesSentThisMonth":5120,"audienceReachable":812 }

// CampaignRecipientResponse
{ "publicId","customerName","destination","channel","status","error","sentAt" }

// SendTestRequest { "to":"+919876543210" }  // phone (WA) or email
```

---

## 4. Drip Sequences

| Method | Path | Auth | Body / Result |
|---|---|---|---|
| GET | `/api/marketing/drips?page&size&sortBy&sortDir&search&status` | READ | `PagedApiResponse<DripSequenceResponse>` |
| GET | `/api/marketing/drips/{publicId}` | READ | `ApiResponse<DripSequenceResponse>` (incl. steps) |
| POST | `/api/marketing/drips` | CREATE | `CreateDripSequenceRequest` → `ApiResponse<DripSequenceResponse>` (201) |
| PUT | `/api/marketing/drips/{publicId}` | UPDATE | `UpdateDripSequenceRequest` (DRAFT/PAUSED; replaces steps) |
| DELETE | `/api/marketing/drips/{publicId}` | DELETE | `ApiResponse<Void>` |
| POST | `/api/marketing/drips/{publicId}/activate` | SEND | DRAFT/PAUSED → ACTIVE (+enroll segment) → `ApiResponse<DripSequenceResponse>` |
| POST | `/api/marketing/drips/{publicId}/pause` | SEND | ACTIVE → PAUSED → `ApiResponse<DripSequenceResponse>` |
| GET | `/api/marketing/drips/{publicId}/enrollments?page&size&status` | READ | `PagedApiResponse<DripEnrollmentResponse>` |

```jsonc
// DripStep (request has no publicId; response includes it)
{ "publicId","stepOrder":1,"name":"Welcome","delayDays":0,
  "channel":"WHATSAPP","subject":null,"body":"Hi {{name}} …","templateName":null }

// CreateDripSequenceRequest / UpdateDripSequenceRequest
{ "name":"Post-booking nurture","description":"…",
  "audienceType":"SEGMENT","segmentPublicId":"…",     // required iff SEGMENT
  "steps":[ {stepOrder,name,delayDays,channel,subject,body,templateName}, … ] }  // ≥ 1

// DripSequenceResponse
{ "publicId","name","description","status","audienceType","segmentPublicId","segmentName",
  "steps":[…], "enrolledCount","activeCount","completedCount",
  "ownerName","createdAt","updatedAt" }

// DripEnrollmentResponse
{ "publicId","customerName","status","currentStep","nextRunAt","enrolledAt","completedAt" }
```
`delayDays` on a step = days to wait **after the previous step** (step 1 = days after enrollment).

---

## 5. Automations (birthday / anniversary triggers)

Tenant-level config: exactly one row per `triggerType`, auto-provisioned (disabled) on first read.

| Method | Path | Auth | Body / Result |
|---|---|---|---|
| GET | `/api/marketing/automations` | READ | `ApiResponse<List<AutomationResponse>>` (both triggers) |
| GET | `/api/marketing/automations/{triggerType}` | READ | `ApiResponse<AutomationResponse>` |
| PUT | `/api/marketing/automations/{triggerType}` | UPDATE | `UpdateAutomationRequest` → `ApiResponse<AutomationResponse>` |
| POST | `/api/marketing/automations/{triggerType}/test` | SEND | `SendTestRequest {to}` → `ApiResponse<Void>` |
| GET | `/api/marketing/automations/upcoming?days=30` | READ | `ApiResponse<List<UpcomingCelebrationResponse>>` |

```jsonc
// AutomationResponse
{ "triggerType":"BIRTHDAY","enabled":true,"channel":"WHATSAPP","daysBefore":0,
  "sendTime":"09:00","subject":null,"body":"Happy birthday {{name}}! …","templateName":null,
  "lastRunOn":"2026-07-14","totalSent":340,"upcomingCount":12 }

// UpdateAutomationRequest
{ "enabled":true,"channel":"WHATSAPP","daysBefore":0,"sendTime":"09:00",
  "subject":null,"body":"Happy birthday {{name}}!","templateName":null }

// UpcomingCelebrationResponse
{ "customerPublicId","customerName","date":"2026-07-20","daysAway":5,"channel":"WHATSAPP","reachable":true }
```
`{triggerType}` path value is `birthday` | `anniversary` (case-insensitive).

---

## 6. Merge tags — `GET /api/marketing/merge-tags` → `ApiResponse<List<MergeTagDef>>`
```jsonc
{ "token":"{{name}}", "label":"Full name", "example":"Rahul Sharma" }
```
Supported tokens: `{{name}}`, `{{firstName}}`, `{{city}}`, `{{state}}`, `{{loyaltyTier}}`,
`{{customerCode}}`, `{{email}}`, `{{phone}}`. Unknown/empty tokens resolve to an empty string.

---

## 7. Delivery & scheduling (backend behavior the UI relies on)

- **WhatsApp** sends reuse `WhatsAppMessagingService` (Interakt) via a new additive
  `sendTemplate(tenantId, phone, templateName|null, bodyValues)` — the merge-resolved body is the
  single template body value; a null `templateName` falls back to the tenant's default template.
- **Email** sends reuse `TenantMailSenderFactory.resolve(tenantId)` (tenant SMTP, global fallback);
  HTML MimeMessage with subject + merge-resolved body.
- **Throttling** reuses `RateLimitService.isAllowed(key,max,window)` per tenant+channel; when a
  window is exhausted mid-batch, remaining recipients stay `PENDING` and resume next scheduler tick.
- **Reachability:** EMAIL needs a non-empty `email`, WHATSAPP a non-empty `phone`; otherwise the
  recipient is `SKIPPED` (never counted as failed).
- **Schedulers** (all per-tenant `TenantContext` set/clear-in-finally, catch-per-tenant, idempotent):
  `CampaignDispatchScheduler` (~1 min: dispatch due + resume SENDING), `DripRunnerScheduler`
  (~5 min: sync segment enrollments + run due steps), `AutomationScheduler` (~hourly: fire
  birthday/anniversary matches once/day via `lastRunOn`).