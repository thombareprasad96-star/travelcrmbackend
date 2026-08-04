# Incoming Leads & Claim — UI Specification

**Scope:** the realtime new-lead alert, the claim race, the unclaimed queue, the ownership audit trail, and the claim/lock affordances that leak into the existing leads screens.
**Backend:** frozen. Every field, endpoint and event named here exists in the verified contract. Nothing new is invented; everything the design wants and cannot have is listed in §10.
**Repo:** `D:\CRM PROJECT\travelcrmfe\travelcrmfrontend` (branch `main`). All paths below are relative to `src/`.

---

## 0. Starting position — read this first

A working claim UI **already exists in the tree, uncommitted**: `app/chrome/LeadAlertHost.jsx`, `features/leads/hooks/useLeadAlerts.jsx`, `features/leads/pages/LeadAlerts.jsx`, `features/leads/components/{LeadAlertRow,leadAlertUi}.jsx`, `features/leads/lib/leadAlertConstants.js`, `features/leads/api/leadAlertService.js`, plus edits to `Layout.jsx`, `Sidebar.jsx`, `router.jsx`, `leads/index.js`, `notificationService.js`, `access.js`.

**This spec is not a greenfield build. It is a re-skin plus a completion.** Three things are wrong with what exists and one thing is missing:

1. **It is built in the wrong visual language.** `LeadAlerts.jsx:123` is the gradient page shell, `:152` is the glass panel, `leadAlertUi.jsx:149-163` is a four-gradient `StatTile`, `:126` is an `animate-pulse` red `NEW` pill. The owner's north star for new screens is Notion/Linear — subtle borders, no heavy shadows, explicitly not the glass/gradient kit. §1a defines the replacement token set; every surface below is specified against it.
2. **The claim loop is incomplete.** `LeadAlerts.jsx:67` answers a lost race with `ctx.refresh()` — a full re-fetch — and throws away `lost.claimVersion`, which `claimLostInfo` already parses. The whole point of the CAS is that the loser re-arms with `details.claimVersion` and clicks again in under a second. §3 fixes this.
3. **Three service methods have zero callers**: `reassign`, `reopenClaim`, `getAssignmentHistory`. `canReassign` is computed in `useLeadAlerts.jsx:188` and read by nothing. `LEAD_REASSIGN_LOCKED` currently gates no pixel. §7 gives them a home.
4. **There is no keyboard model at all**, and the north star is keyboard-first for operators whose metric is time-to-claim. §3 defines it.

Two structural defects to fix on the way through: `Layout.jsx:86` statically imports `@features/leads`, which drags AllLeads (2012 lines) + CreateLead (2595) into the initial bundle (the build logs `INEFFECTIVE_DYNAMIC_IMPORT`); and an authenticated tab holds **two** EventSource connections because `Navbar.jsx:206` still uses the legacy function form of `subscribeToSSE` while `useLeadAlerts.jsx:111` uses the map form. Both are addressed in §1.

---

## 1. Surface inventory

| # | Surface | File | Who sees it | Why it must exist | Rank |
|---|---|---|---|---|---|
| 1 | **Alert stack** — the live broadcast popup, bottom-right, on every authenticated page | `app/chrome/LeadAlertHost.jsx` (rewrite) | Any user with `LEAD_READ`. Claim button additionally gated on `LEAD_CLAIM` | The race is the product. A lead broadcast at 14:32 that is only visible on `/leads/incoming` is a lead lost to whoever had that tab open. Covers S1/S2 and the reopen re-broadcast (S9) | **P0** |
| 2 | **Incoming queue page** — the durable list of everything open to claim | `features/leadalerts/pages/LeadAlerts.jsx` (moved + re-skinned) | `LEAD_READ` (route + sidebar gate) | The single source of truth. Every broadcast is `afterCommit` best-effort and swallows failures; SSE has no `Last-Event-ID` replay. `GET /api/leads/alerts/open` is the only recoverable channel — S5, and every race in the matrix that ends "re-fetch" | **P0** |
| 3 | **Claim control** — the button, its version token, its four failure shapes | `features/leadalerts/components/ClaimButton.jsx` + row/card call sites | `LEAD_CLAIM` | Without correct `expectedClaimVersion` re-arming, a loser's button can never win again (`LeadClaimConcurrencyIT:140-163`) | **P0** |
| 4 | **Live/offline + backfill strip** — connection state and the reconnect gap | `features/leadalerts/components/ConnectionStrip.jsx` | `LEAD_READ` | Broadcasts fail silently while connected (`LeadAlertBroadcaster.send` swallows everything). The user must be able to tell "quiet" from "broken" | **P0** |
| 5 | **Provider / SSE fan-in** | `features/leadalerts/hooks/useLeadAlerts.jsx` (moved) | — | One subscription, many readers. Owns the tick, the upsert, the resync, `receivedAt` stamping | **P0** |
| 6 | **Sidebar live badge** on *Incoming leads* | `app/chrome/Sidebar.jsx:182` | `LEAD_READ` + `hasModule("LEADS")` | A dismissed alert must remain findable in one glance. Reads `leads.length` off the provider — no new fetch | **P1** |
| 7 | **Assignment history drawer** — the ownership timeline | `features/leadalerts/components/LeadHistoryDrawer.jsx` | `LEAD_READ` (backend widens it to claimable-but-invisible leads) | S8 (contacted by someone other than the owner) is invisible on every other screen: `LeadResponseDto` has `firstContactedAt` but not `firstContactedBy*`. Only `GET /{publicId}/assignment-history` can tell that story | **P1** |
| 8 | **Manager actions** — Reassign, Reopen claim window | inside the history drawer, `components/ReassignPanel.jsx`, `components/ReopenPanel.jsx` | `LEAD_REASSIGN_LOCKED` | S6/S7. Locked leads never appear in the queue, so these actions need a home reachable from AllLeads and the lead detail | **P1** |
| 9 | **Lead-detail claim header** — strip at the top of the edit page | `features/leads/pages/CreateLead.jsx` (edit mode) | `LEAD_READ`; actions per key | The queue is not the only entry point. `LeadResponseDto` already carries `claimVersion`, `openToClaim`, `firstContactedAt`, `firstResponseSeconds`, `slaTargetSeconds` — a claim header needs no new call | **P1** |
| 10 | **AllLeads awareness** — open/locked marker in the Assigned cell + a top strip | `features/leads/pages/AllLeads.jsx` (`LEAD_COLUMNS[10]`, render at `:782-792`) | `LEAD_READ` | Today the two screens tell different stories about the same lead. Same DTO, no extra fetch | **P1** |
| 11 | **Shortcut cheatsheet** (`?`) | `features/leadalerts/components/ShortcutHelp.jsx` | queue page only | Keyboard-first is only real if it is discoverable | **P2** |
| 12 | **Sound toggle** | queue header, writes `localStorage["leadAlertSound"]` | all | Shared desks; one agent's chime is another's interruption | **P2** |
| 13 | **Bell deep-link** — `NOTIF_ROUTE_MAP` entry so a `LEAD_CREATED` notification lands on the queue | `app/chrome/Navbar.jsx:247-253` | all | The persisted `LEAD_CREATED` in-app notification is the *only durable trace* of a missed alert. It currently routes to `/allleads`, where a claimable-but-out-of-scope lead (S4) is not present | **P2** |

### 1a. Structural prerequisites (do these first — they are not optional)

**P0 — `subscribeToSSE` needs an `onOpen` key.** `notificationService.js:224-229` maps server event names to handler keys and exposes only `onError`. The provider cannot distinguish "connected" from "never errored", and cannot backfill after a reconnect. Add `onOpen` to the normalised option map, invoked from `es.onopen` (`:277`) alongside the existing backoff reset. Everything in §9 depends on this.

**P0 — one connection per tab.** Migrate `Navbar.jsx:196-212` off the legacy function form onto the shared provider: the bell already sits *below* `LeadAlertProvider` in `Layout.jsx`, so it can consume a `notification` handler exposed by the provider instead of opening its own EventSource. Two connections per tab doubles server emitters for no benefit, and a third consumer compounds it.

**P1 — extract the subsystem into `src/features/leadalerts/`.** Pure file move: `hooks/useLeadAlerts.jsx`, `api/leadAlertService.js`, `lib/leadAlertConstants.js`, `components/leadAlertUi.jsx`, `components/LeadAlertRow.jsx`, `pages/LeadAlerts.jsx` → new feature with its own `index.js`. `Layout.jsx` then imports `@features/leadalerts` (a small chunk) instead of `@features/leads` (a 6000-line one), restoring code-splitting. `router.jsx:15,23` gets a second thunk; `AllLeads.jsx` imports the open-count strip from `@features/leadalerts` — cross-feature via the barrel, which the boundary rule permits.

Rationale for a separate feature rather than a folder inside `leads`: this is the only surface in the app that mounts in **chrome** rather than on a route. That is what makes it a peer of `leads`, not a part of it.

### 1b. Design tokens — the Notion/Linear layer

The existing kit (`leadAlertUi.jsx`) keeps its *structure* and its `sourceKey` colour map and loses its *skin*. Replace as follows. These tokens apply to surfaces 1, 2, 3, 4, 7, 8, 11 and to the strips added in 9 and 10. Do not mix them with `marketingUi`/`accountingUi`/`fleetUi` on the same screen.

| Token | Value | Replaces |
|---|---|---|
| Page shell | `min-h-screen bg-white` + `mx-auto max-w-[1180px] px-6 py-5` | `bg-gradient-to-br from-slate-50 via-blue-50/30 to-slate-100` (`LeadAlerts.jsx:123`) |
| Panel | `rounded-lg border border-slate-200` — no backdrop-blur, no shadow | `bg-white/80 backdrop-blur-md rounded-2xl shadow-sm` (`:152`) |
| Divider | `border-b border-slate-100` | same |
| Row | `h-11 px-3 hover:bg-slate-50` — border-bottom only, no card, no per-row rounding | `rounded-2xl border p-3.5` cards (`LeadAlertRow.jsx:45`) |
| Row focus | `bg-blue-50/60 ring-1 ring-inset ring-blue-500/40` | none today |
| Source rule | `2px` left rule, colour from `sourceStyle(sourceKey).color` — keep | `4px` (`LeadAlertRow.jsx:48`) |
| Stat tile | `rounded-lg border border-slate-200 px-4 py-3`, label `text-[11px] uppercase tracking-wide text-slate-500`, value `text-2xl font-semibold text-slate-900 tabular-nums` | 4 gradients + `shadow-lg` (`leadAlertUi.jsx:149-163`) |
| Primary button | `h-8 rounded-md bg-blue-600 px-3 text-[13px] font-medium text-white hover:bg-blue-700` | keep, drop `font-bold`, `rounded-[10px]` |
| Secondary button | `h-8 rounded-md border border-slate-200 px-3 text-[13px] font-medium text-slate-700 hover:bg-slate-50` | green-tinted Contact button (`leadAlertUi.jsx:229`) |
| Badge | `rounded px-1.5 py-0.5 text-[11px] font-medium` + tone tint; never `font-extrabold`, never `animate-pulse` | `NewPill` (`:126`), `ContactedPill` (`:134`) |
| Type scale | body `13px`, meta `12px`, label `11px uppercase tracking-wide`; weights 400/500/600 only | 14.5/12.5/11 with `font-bold`/`font-extrabold` throughout |
| Numerics | `tabular-nums` on every countdown, value and count | absent |
| Elevation | **only** overlays: alert card `shadow-[0_10px_30px_-12px_rgb(15_23_42/0.25)]`, drawer `shadow-[0_0_0_1px_rgb(226_232_240),-16px_0_40px_-24px_rgb(15_23_42/0.3)]` | `shadow-2xl`, `shadow-lg` on inline surfaces |
| Motion | 120ms ease-out for hover/press; 200ms slide for the alert card; nothing pulses. All animation behind `@media (prefers-reduced-motion: no-preference)` | `animate-pulse` on NEW pill and crit SLA chip |
| Font | keep `FONT_STACK` set inline (nothing sets `font-family` globally) | keep |

---

## 2. The alert surface

### Decision

**A bottom-right docked stack of up to three compact cards, appended upward, auto-expiring after 10s, never focus-stealing, deduped by `leadPublicId`, with one throttled chime.** Mounted once in `Layout.jsx` beside `ReminderPopupCenter`, so it fires on every authenticated route.

### Defence against the alternatives

- **Modal / centre-screen interrupt** — disqualified outright. An agent mid-way through `CreateBookingClean` gets their keystrokes eaten and their form state put at risk by an `Esc`. Several can arrive at once, and a modal queue is a trap. The alert is *information about an opportunity*, never a decision the app is entitled to force.
- **Bell-only** (badge on `Navbar`) — disqualified for the primary channel. The metric is time-to-claim; a badge costs a glance plus a click plus a read before the race even starts. It stays as the *durable* channel (the persisted `LEAD_CREATED` notification is the only record of a missed alert) and gets a route-map entry (surface 13), but it cannot be the alert.
- **Docked panel / permanent right rail** — disqualified. It permanently narrows every page in the app for an event that fires a few times an hour, and it competes with `ReminderPopupCenter`, the two reminder bells, and `ImpersonationBanner` for the same edge.
- **Top-right toast** (what exists today, `LeadAlertHost.jsx:128`) — right idea, wrong corner and wrong growth direction. `ToastHost` is `fixed right-5 top-5 z-[999]` (`toast.jsx:136`), so today's `z-[80]` alert card is painted over by its own success toast. Moving the alert stack to the bottom-right resolves the collision without touching the shared host.

### Position, stacking, growth

```
container: fixed bottom-5 right-5 z-[120] flex flex-col-reverse gap-2
           w-[360px] max-w-[calc(100vw-2.5rem)] pointer-events-none
card:      pointer-events-auto
```

`flex-col-reverse` over an arrival-ordered array anchors the **oldest card to the bottom corner and inserts new ones above it**. Existing cards therefore never move. This is the anti-misclick rule and it is not negotiable: an agent reaching for *Claim* on the card that just chimed must not have a newer lead slide under the cursor between mousedown and mouseup.

- Max **3** cards rendered. Overflow collapses to one 24px bar pinned below the stack: `+4 more incoming · ⌥I`. Overflow leads are never lost — they are in the queue and in the sidebar badge.
- `aria-live="polite"` on the container, `aria-atomic="false"`. Never `assertive`: that yanks a screen-reader user out of the form they are filling.
- **Nothing autofocuses.** The host renders last in `Layout`'s DOM so its buttons land at the end of the tab order. A user tabbing through a booking form never falls into the stack.

### Dedupe

Cards are keyed on **`leadPublicId`**, not on a synthetic `toastId` (today's `useLeadAlerts.jsx:116` mints `${leadPublicId}-${Date.now()}`, which duplicates a reopened lead).

| Incoming | Card exists for that `leadPublicId`? | Behaviour |
|---|---|---|
| `lead-alert`, `actorName == null` | no | insert, chime, start 10s timer |
| `lead-alert`, `actorName != null` (a reopen — `LeadClaimService:312`) | either | upsert in place, reset timer, tag the card `REOPENED`, **no chime** |
| `lead-claimed`, new owner is not me | yes | keep the card, replace the owner line with `Now with {ownerName}`, **re-arm the Claim button with the payload's bumped `claimVersion`** (the lead is still `openToClaim`), shorten the remaining timer to 3s |
| `lead-claimed`, new owner is me | yes | remove the card (my other tab won it — `SseEmitterRegistry` fans out to every tab of the same user) |
| `lead-locked` (`openToClaim === false`) | yes | remove the card immediately |
| `lead-locked` / `lead-claimed` | no | **never insert.** Only `lead-alert` may create a card |

### Sound

One two-note WebAudio chime (keep `LeadAlertHost.jsx:60-83` — no bundled asset, try/catch, `localStorage["leadAlertSound"] === "off"` kill switch). Additional rules:

- **Throttle: one chime per 3000ms.** A burst of five leads gets one chime, not five.
- Never chime on a reopen (`actorName != null`) or on `lead-claimed`/`lead-locked`.
- Chime regardless of `document.visibilityState` — a backgrounded tab is exactly the case the sound is for.
- The toggle lives in the queue header (surface 12) and in no other place.

### Timing and dismissal

- **10s** per card (up from today's 8s — reading a card and deciding takes longer than reading a toast).
- The progress bar stays but becomes a 2px slate-200 track with a blue-600 fill; under `prefers-reduced-motion: reduce` it is replaced by a static `10 · 9 · 8` counter in the card's meta line.
- **Hover or keyboard focus anywhere in the stack pauses every timer.** Resume on leave.
- Manual dismiss: the `×` per card, or `⌥X` for the newest.
- **After expiry the lead is not gone.** It is in the queue, in the sidebar badge, and in the bell. Expiry is a display decision only. This is what makes a docked stack acceptable where a modal would not be.
- Navigation survival: the host is in `Layout`, above `<Outlet/>`, so a route change re-renders the page and leaves the stack untouched. A full page reload drops the stack — recovery is `GET /api/leads/alerts/open` on mount, which is the same path as a missed broadcast.

### Card content — exact fields

`customerName` · `leadCode` · `SourceBadge(sourceKey, source)` · `destination` · `paxSummary(adults, children, infants)` · `formatMoney(value)` · `phone` · `Auto-assigned to {ownerName}` · live SLA chip from `slaSecondsRemaining`. Nothing else is available (`LeadAlertDto` deliberately carries no email, notes or itinerary), and nothing else is needed to answer "do I take this call".

---

## 3. The claim interaction

### Placement and copy

| Context | Control | Copy | Notes |
|---|---|---|---|
| Alert card | primary, full-width-left of a 2-up row | `Claim` + `⌥C` hint on the newest card only | secondary is `View` → `/leads/incoming` |
| Queue row | rightmost cell, always in the same x-position across rows | `Claim` | the label never changes width between rows; that column is a target zone |
| Queue row, I already own it | static chip, not a button | `Yours` | `ClaimButton owned` today renders `You own it` with a checkmark — keep the semantics, shorten the copy |
| Queue row, after a `VERSION_STALE` loss | primary, re-armed | `Claim anyway` | see LOST below |
| Lead-detail header (`openToClaim === true`) | primary | `Claim` | |
| Anywhere, no `LEAD_CLAIM` | **hidden**, not disabled | — | a permanently dead button is noise; `LeadAlertRow.jsx:105` already gets this right |

Drop the lightning-bolt icon and `Claim & override`. "Override" describes the backend's semantics, not the operator's intent, and it reads as a warning on the one action the design wants to be frictionless. The queue header carries the explanation once (`auto-assigned by workload · open to claim until contacted`); the button does not need to re-argue it.

### Keyboard model

**Global** (any authenticated page, only while the stack has ≥1 card; all require a modifier so they cannot fire mid-typing, and all bail if `event.target` is `input`/`textarea`/`[contenteditable]`):

| Key | Action |
|---|---|
| `⌥C` / `Alt+C` | Claim the **newest** card (the one carrying the hint badge) |
| `⌥X` / `Alt+X` | Dismiss the newest card |
| `⌥I` / `Alt+I` | Navigate to `/leads/incoming` |

**Queue page** (bare keys are safe here — the page has one text input and every handler checks the target):

| Key | Action |
|---|---|
| `j` / `↓`, `k` / `↑` | Move row focus (roving `tabIndex`, `scrollIntoView({block:"nearest"})`) |
| `Enter` | Expand / collapse the focused row's detail |
| `c` | Claim the focused row |
| `m` | Mark contacted the focused row |
| `h` | Open the assignment-history drawer for the focused row |
| `/` | Focus the filter input |
| `Esc` | Collapse the row, or close the drawer, or blur the filter — in that order |
| `?` | Shortcut cheatsheet |

Row focus is preserved by `leadPublicId` across every upsert. If the focused row is removed (locked by someone else), focus moves to the row that took its index — never to `document.body`.

### Optimistic or pessimistic

**Pessimistic on ownership, optimistic on affordance.** The CAS can genuinely lose; flipping the row to `Yours` and reverting 300ms later is a worse experience than 300ms of honest pending. So:

- On click: that row's button only → label `Claiming…`, `disabled`, `aria-busy`. Nothing else in the list changes. No global spinner, no list-level loading state.
- Repeat clicks are swallowed while `busy` is set on that `leadPublicId` (the backend runs `AssignableUserResolver.resolve` — a full tenant user + permission read — on *every* claim; a fire-and-retry UI is a load problem).
- `expectedClaimVersion` is **the version this user saw**, never a re-read. `leadAlertService.js` already documents this; keep it.

### WON

1. `applyResult(result)` merges `LeadClaimResultDto` onto the row (`useLeadAlerts.jsx:167` merges rather than replaces — correct, the result DTO has no `phone`/`source`/pax; keep it).
2. The row's owner cell flips to `Yours`, and the row gets a 1.2s `bg-blue-50` flash that decays to normal.
3. **Focus moves to that row's `Mark contacted` button.** The race is over; the SLA clock is still running and is now the operator's problem. This is the single most important attention hand-off in the feature.
4. `toast.success(\`Claimed — ${customerName || leadCode}\`)` — one line, 3.5s, the shared host. Nothing richer; the row already tells the story.
5. If the claim was made from an alert card, the card is removed and the toast is the only confirmation (there is no row to speak).

### LOST — the 409

`claimLostInfo(err)` (already implemented) yields `{reason, ownerName, claimVersion, message}`. Branch on `reason`. **The explanation is rendered on the row, never as a toast** — the shared host's 4s dedupe window and 6s TTL would scroll the answer away before the operator looks back at the list, and the whole point is that they can see who has it now.

| `reason` | Row treatment | Button | Copy (use the server's `message`, these are the fallbacks) | Then |
|---|---|---|---|---|
| `VERSION_STALE` | amber note under line 2 | **re-armed with `details.claimVersion`**, label `Claim anyway` | `Priya claimed this a moment ago.` | Row stays. Retryable — this is the retry loop the backend exists to support. Do **not** call `refresh()` (today's `LeadAlerts.jsx:67` does; it costs a round-trip and re-races) |
| `ALREADY_CONTACTED` | row transitions to the locked treatment in place | Claim removed, `History` remains | `Priya contacted this first — the claim window is closed.` | Row fades out after **6s**, not instantly. The operator must be able to read why |
| `TERMINAL_STAGE` | locked treatment, slate | Claim removed | server message names the stage (`This lead has been marked Lost.`) | fade out after 6s |
| `NOT_LOCKED` | only reachable from reassign/reopen | swap the panel's action for `Claim` | `This lead is still open — claim it instead.` | route the user to the claim control |
| **404** (S13 soft-deleted, or S16 post-lock scope cliff — the backend cannot distinguish them and neither can we) | slate note | all actions removed | `This lead is no longer available.` | fade out after 4s, silently. Never an error toast |
| **403 `PERMISSION_DENIED` with the pool message** (S15) | persistent note, does not fade | Claim hidden **for the rest of the session** (`sessionStorage["leadClaimIneligible"]`) | render the server's own copy: `Your account cannot be assigned leads, so it cannot claim one.` | Do not fall through to the generic access toast |
| **403 `MODULE_NOT_ENABLED`** | — | — | handled by the interceptor's upgrade path; branch on `code`, not status | |

**Where the loser's attention goes:** after a terminal loss (`ALREADY_CONTACTED`, `TERMINAL_STAGE`, 404), keyboard focus advances to the **next open row** as the losing row fades. The fastest recovery from losing a lead is claiming the next one, and the design should put the operator one keystroke from it.

Two 409 shapes exist on the same buttons: `LeadClaimLostException` carries `details.claimLost`; plain `BusinessException` 409s (reopen-when-open, reopen-past-Contacted) carry nothing. **Branch on `details.claimLost` and fall back to `message`** — never on status alone.

Note that a *lost* claim broadcasts nothing (`LeadClaimServiceTest:229-247`). No corrective event will arrive. The row's state after a loss must come entirely from the 409 body.

---

## 4. The unclaimed queue

### Decision: a separate route, `/leads/incoming`

Already registered (`router.jsx:283-291`, guarded on `P.LEAD_READ`; sidebar item at `Sidebar.jsx:182`). Keep it. Not a tab inside `AllLeads`, not a filter.

**Defence.** The two lists are different data with different rules and merging them produces bugs, not convenience:

- **Scope.** `/alerts/open` is *tenant-wide and deliberately not row-scoped*; `GET /api/leads` is row-scoped. Fold the queue into AllLeads as a tab and an OWN-scoped agent gets rows that vanish when they switch tabs, and rows whose detail page 404s (S4).
- **Volume model.** AllLeads fetches once on mount, `size=100`, and filters client-side (`AllLeads.jsx:1468`, `leadService.js:267`). The queue is a live-upserted, SSE-fed, 200-capped feed with a 45s reconciliation. Sharing one state container means one of them is wrong.
- **Task shape.** AllLeads is a 16-column pipeline management table (`LEAD_COLUMNS`, `:142-159`) with a `LEAD_TABLE_MIN_W` horizontal scroller. The queue is a decision surface where 8 fields must be readable without scrolling and one button must be hit in under a second.

What *does* connect them: a strip at the top of AllLeads — `12 leads open to claim · Incoming →` — and the open/locked marker in the Assigned cell (surface 10).

### Columns

One CSS grid template shared by the header, every row and the skeleton:

```
grid-cols-[2px_minmax(200px,1.4fr)_104px_minmax(150px,1fr)_96px_150px_84px_max-content]
```

| # | Column | Source | Rendering |
|---|---|---|---|
| 1 | source rule | `sourceStyle(sourceKey).color` | 2px full-height left rule. Keyed on `sourceKey` (enum name), **never** on `source` (display prose) |
| 2 | Customer | `customerName`, `leadCode` | name `text-[13px] font-medium text-slate-900`; `leadCode` `text-[11px] text-slate-400 tabular-nums`. `null` name → `Unnamed enquiry`. `leadCode` is null on un-backfilled rows — render nothing, not `—` |
| 3 | Source | `sourceKey` / `source` | text label + a 6px dot in the source colour. Not the filled pill; a wall of coloured pills at 11px is what the Linear idiom exists to avoid |
| 4 | Enquiry | `destination`, `paxSummary(...)` | `Bali · 2 Adults, 1 Child`. `destination` is null when `departCity` was blank or literally `"Not Specified"` — then show pax alone |
| 5 | Value | `formatMoney(value)` | right-aligned, `tabular-nums`. Null renders empty, **never `₹0`** |
| 6 | Owner | `ownerName`, `ownerPublicId` | `Yours` (blue-600 text) / `{ownerName}` (slate-600). `assigned_user_id` is NOT NULL server-side — never render "Unassigned" |
| 7 | SLA | `slaSecondsRemaining` → `secondsLeft` | see §6 |
| 8 | Actions | | `Call` `WhatsApp` icon links (`QuickActions`, keep) · `Claim` · `Mark contacted` · `⋯` (history / reassign / reopen) |

Expanded row (`Enter`) adds a two-line `dl`: `phone`, `leadStage` (display name — `New Lead`, not `NEW_LEAD`), `createdAt` rendered as an absolute local time (`Received 14:32`), and `previousOwnerName` / `actorName` when the row arrived over `lead-claimed` (`Taken from Rakesh by Priya`) — these are null on `/alerts/open` rows, so the line only appears on live-updated rows.

### Sort

**Fixed: newest first, exactly as the server returns it** (`ORDER BY l.createdAt DESC`). No sort controls. In a race, arrival order *is* the priority order, and a sortable column invites an operator to re-order the one list where re-ordering costs them leads. The one exception is the `Breached` filter below, which surfaces the stale tail without disturbing order.

### Filters

A single `h-8` filter row above the list, all client-side over the in-memory array:

- Text input (`/` to focus, `Esc` to clear): matches `customerName`, `phone`, `leadCode`, `destination`. Placeholder: `Filter by name, phone, code…`
- Segmented control: `All` · `Mine` · `Breached`. `Mine` compares owner identity (see §10 gap 3). `Breached` is `secondsLeft != null && secondsLeft <= 0` — and must be labelled `Past target`, not `Breached`, because a reopened lead (S9) shows a deeply negative countdown while `slaBreached` is false.

No date filter. Every row in this feed is by definition still open; a date range on it is meaningless.

### Live insertion

- A new `lead-alert` inserts at the top with a 1.5s left-edge blue flash. Under `prefers-reduced-motion` the flash is a static blue left rule that clears on the next tick.
- **The list never auto-scrolls.** If the container is scrolled more than 40px from the top when a lead arrives, insert silently and show a sticky pill at the top of the scroller: `2 new leads ↑` — click or `Home` jumps to top and clears it.
- Row focus is preserved by key across every upsert, including re-orders.
- `lead-claimed` updates in place (owner cell + `claimVersion`); the row does not move, does not flash.
- `lead-locked` removes the row with a 200ms collapse — unless *this* user's action caused it, in which case the row is already gone via `applyResult`.
- `lead-locked` for a `leadPublicId` not in the list is a no-op. Never insert on `lead-locked` (post-lock reassign reuses that event name).

### Empty state

```
No leads waiting
Every enquiry has been picked up. New ones appear here the moment they arrive.
```

Icon: `Inbox` 20px slate-300, no coloured tile. If `stats.openToClaim > 0` while `leads.length === 0`, this is a lie — see §9's stale/truncation banner.

### Header

`Incoming leads` · `LivePill` (re-skinned: an 6px dot + `Live` / `Reconnecting…` / `Offline`, no `LIVE · all users` shouting) · sound toggle · `?`. Below it, the four stat tiles (§6), then the filter row, then the list. The source legend strip (`LeadAlerts.jsx:166-170`) is **deleted** — six hardcoded source pills that are not the tenant's actual sources are decoration, and the per-row dot plus label carries the same information where it is needed.

---

## 5. State vocabulary

One table. `Actions` lists what is *visible*; anything not listed is hidden, not disabled.

| State | Wire condition (client-detectable) | Badge / label | Colour | Icon | Actions | Appears in queue? |
|---|---|---|---|---|---|---|
| **S1** Open, inside SLA | `openToClaim === true`, `secondsLeft > 0` | `Open` | `text-slate-500` (no chip — open is the default, it needs no badge) | — | Call · WhatsApp · `Claim` (if `LEAD_CLAIM`) · `Mark contacted` (if `LEAD_UPDATE`) · `⋯` | yes |
| **S2** Open, past target | `openToClaim === true`, `secondsLeft <= 0` | `Past target +2:14` | `bg-red-50 text-red-700 border-red-200`, row tint `bg-red-50/30` | `AlertCircle` 12px | identical to S1 — breaching blocks nothing | yes |
| **S3** Open, owned by me | S1/S2 + owner is me | owner cell `Yours` | `text-blue-700 font-medium` | — | Call · WhatsApp · `Mark contacted` · `⋯`. **`Claim` hidden** (a self-claim is a 200 no-op with no version bump and no event; two tabs must not fight) | yes |
| **S4** Open but un-openable | indistinguishable client-side | — | — | — | same as S1; the row's expand panel is the only detail surface. Never link the name to `/EditLead/:id` from the queue — it 404s | yes |
| **S5** Feed truncated | `leads.length >= 200 \|\| stats.openToClaim > leads.length` | banner above the list | `bg-amber-50 border-amber-200 text-amber-800` | `AlertTriangle` | `Showing the 200 newest of {stats.openToClaim} open leads.` | — |
| **S6** Locked at Contacted | `openToClaim === false`, `leadStage === "Contacted"` | `Contacted` | `bg-slate-100 text-slate-600` | `Lock` 11px | `History` · `Reassign`/`Reopen` (if `LEAD_REASSIGN_LOCKED`) | no — removed on `lead-locked` |
| **S7** Locked, progressed | `openToClaim === false`, `leadStage` ∈ `Follow Up`/`Qualified`/`Proposal Sent` | the stage display name | `bg-slate-100 text-slate-600` | `Lock` | `History` · `Reassign`. **`Reopen` hidden** — the backend refuses anything past Contacted | no |
| **S8** Contacted by someone else | `firstContactedByName != null && firstContactedByName !== ownerName` (only from `LeadClaimResultDto` / history) | `Contacted by {firstContactedByName}` in the history drawer and the detail header | `text-slate-500` | `Lock` | as S6 | no |
| **S9** Reopened claim window | `openToClaim === true` **and** `firstResponseSeconds != null` | `Reopened` | `bg-violet-50 text-violet-700 border-violet-200` | `RotateCcw` 12px | as S1 | yes |
| **S10** Converted | `leadStage === "Converted"` | `Converted` | `bg-emerald-50 text-emerald-700` | `CheckCircle2` | `History` only | no (leaves silently — see §9) |
| **S11** Lost | `leadStage === "Lost"` | `Lost` | `bg-slate-100 text-slate-500` | `XCircle` | `History` only | no (leaves silently) |
| **S12** Zombie reopened | `openToClaim === true`, `leadStage === "Reopened"`, `createdAt` days old | `Reopened` + age `· 6d old` | violet badge, slate age | `RotateCcw` | as S1 | yes — the row must render an age in weeks without breaking |
| **S13** Soft-deleted | only ever surfaces as a 404 | inline `This lead is no longer available.` | `text-slate-500` | — | none; fade out 4s | — |
| **S14** Legacy un-backfilled | `slaTargetSeconds == null` on a `LeadResponseDto` | SLA cell renders `—` | slate | — | unchanged | — |
| **S15** Cannot own | 403 with the pool message | persistent inline note | `bg-amber-50 text-amber-800` | `Info` | `Claim` hidden for the session; `Mark contacted` still shown (the backend allows it) | yes |
| **S16** Post-lock scope cliff | 404 on any claim-window call | as S13 | slate | — | none; fade out 4s, no error toast | — |
| **S17** Contacted, unmeasured | `firstContactedAt != null && firstResponseSeconds == null` | SLA cell `—` | slate | — | as S6 | no |

Cross-cutting rules:

- `leadStage` is always the **display name** (`"Follow Up"`). `LeadAssignmentEventDto.eventType` / `.strategyUsed` are the opposite — **enum names** (`AUTO_ASSIGNED`, `LOAD_BASED`) and need their own label map (§7).
- `openToClaim` is a **boolean** on `LeadAlertDto`/`LeadClaimResultDto`/`LeadResponseDto` and a **count** on `LeadAlertStatsDto`. Never let the two meet in one destructure.
- An unknown `sourceKey` falls through to `DEFAULT_SOURCE` with the server's own `source` label (`sourceStyle` already does this) — a backend-added source shows in slate with the right text, never blank.
- Unknown `leadStage` strings render raw. Copy the `AllLeads.jsx:561` prepend-unknown-enum idiom; never let a stage the FE hasn't heard of render as empty.

---

## 6. Time and SLA display

### The countdown

Derived, never counted down step by step:

```
secondsLeft = slaSecondsRemaining − (Date.now() − receivedAt) / 1000
```

`receivedAt` is stamped when the payload lands (`useLeadAlerts.jsx:43`). This is the only correct formula available: `slaSecondsRemaining` is server-computed and authoritative at the instant of receipt, and `createdAt` is a zoneless `LocalDateTime` that would have to be interpreted against an untrusted browser clock. **Never compute the countdown from `createdAt`.**

`createdAt` is used for exactly one thing: the absolute *received at* line in the expanded row and the drawer, and the coarse age (`6d old`) on S12 rows. Both are tolerant of minutes of skew; the countdown is not.

### Rendering

| `secondsLeft` | Text | Tone |
|---|---|---|
| `> 40% of slaTargetSeconds` | `4:12` | `text-slate-500`, no chip |
| `20–40%` | `1:47` | `bg-amber-50 text-amber-700 border-amber-200` |
| `0–20%` | `0:38` | `bg-red-50 text-red-700 border-red-200` |
| `<= 0` | `+2:14` (counts **up**) | `bg-red-50 text-red-700 border-red-200`, row tint `bg-red-50/30` |
| `null` (clock stopped) | `answered in 3m` when `firstResponseSeconds != null`, else `—` | `text-slate-400` |

Keep `slaTone`'s proportional bands (`leadAlertConstants.js:103-111`) — thresholds derived from the lead's own `slaTargetSeconds`, never hardcoded 300. Change `formatCountdown` to emit `+m:ss` for negatives instead of the string `BREACH`: "how far past" is actionable, "BREACH" is a scold. Remove `animate-pulse` from the critical chip.

**`null` is "clock stopped", not zero.** Same rule for `avgFirstResponseSeconds` — render `—`, never `0m` (the current `LeadAlerts.jsx:110` gets this right; keep it).

**Never derive "breached" from the countdown.** A reopened lead (S9) can show `+18:42` while `slaBreached === false`, because `firstResponseSeconds` is frozen from the original response. Breach truth comes from `slaBreached` (`LeadClaimResultDto`) or `firstResponseSeconds > slaTargetSeconds`. The queue's filter is therefore labelled `Past target`, not `Breached`.

### Tick

- **One 1s interval in the provider** for the whole list (`useLeadAlerts.jsx:140-143`). Never one per row.
- **Wrap `LeadAlertRow` in `React.memo`** and pass `secondsLeft` as a primitive. Without this, a 200-row feed re-renders 200 rows per second.
- **Pause the tick when `document.visibilityState !== "visible"`.** Nothing on a hidden tab needs a countdown, and browsers throttle the interval anyway.

### Clock skew and tab sleep

`LeadAlertDto` has no `serverTime`/`emittedAt` (§10 gap 1), so `slaSecondsRemaining` is only accurate at the instant of receipt and there is no way to re-base it. Mitigation:

- On `visibilitychange → visible`, if the tab was hidden **> 60s**, call `refresh()` before the next paint. Every row gets a fresh server-computed `slaSecondsRemaining` and a fresh `receivedAt`.
- The 45s reconciliation poll (§9) re-bases everything anyway; the visibility hook only closes the laptop-lid gap.
- Absolute times (`createdAt`, `firstContactedAt`, `occurredAt`) are rendered with `toLocaleString()` and are therefore in the **browser's** zone, while the stat tiles are computed in the **tenant's** zone. Label the tiles accordingly (`Today` → tooltip `Your agency's local day`) and never build a client-side "today" filter that claims to agree with them.

### The tiles

Four tiles, three different time windows, and the UI must say so:

| Tile | Field | Caption (required — it is the only thing preventing a wrong reading) |
|---|---|---|
| `New today` | `newToday` | `created today` |
| `Open to claim` | `openToClaim` (the **count**) | `all time, not just today` |
| `Avg first response` | `avgFirstResponseSeconds` → `—` when null | `contacted today · target {slaTargetSeconds/60}m` — read the target from the payload, never hardcode 5m |
| `Past target` | `slaBreaches` | `among today's leads` |

`slaBreaches` grows with elapsed time even with no writes and the backend never pushes it. Re-fetch `/alerts/stats` on every `lead-alert` and `lead-locked` (already done at `useLeadAlerts.jsx:120,125`) **and** on a 60s timer while the queue page is mounted. Never increment it client-side.

---

## 7. Assignment history and manager actions

### Form: a right-side drawer, 480px

`features/leadalerts/components/LeadHistoryDrawer.jsx`. Opened by `h`, by the row's `⋯` menu, from the AllLeads row menu, and from the lead-detail claim header. A drawer rather than a modal because the operator's context — the queue, the lead row — must stay on screen; and rather than an inline expansion because it must be reachable from three different lists.

Fetch on open: `leadAlertService.getAssignmentHistory(publicId)` (already implemented, currently zero callers).

### Shape

```
Header:  {customerName}  {leadCode}
         Owner {ownerName} · {stage badge} · {SLA state}
Body:    vertical timeline, OLDEST FIRST, exactly as returned
Footer:  manager actions (LEAD_REASSIGN_LOCKED only)
```

**Render in the array's order. Never re-sort by `occurredAt`** — the backend orders by DB `id` precisely because a claim and the contact that follows it can share a second.

Event row: 8px dot + rail, `toUserName`/`fromUserName`, `actorName`, `note`, `occurredAt` (time only when it shares a day with the row above).

| `eventType` (enum name) | Label | Dot | Line |
|---|---|---|---|
| `AUTO_ASSIGNED` | `Auto-assigned` | slate-400 | `to {toUserName}` + a `{strategyUsed}` chip (`LOAD_BASED` → `Load balanced`, `ROUND_ROBIN` → `Round robin`, `SELF` → `Self`, `MANUAL` → `Manual`). `actorName` is null here — machine-created |
| `CLAIMED` | `Claimed` | blue-500 | `by {actorName}` · `from {fromUserName}` |
| `CONTACTED` | `Contacted` | emerald-500 | `by {actorName}`. `fromUserName`/`toUserName` are both null — **do not render an ownership change** |
| `REASSIGNED` | `Reassigned` | amber-500 | `{fromUserName} → {toUserName}` · `by {actorName}` |
| `REOPENED` | `Claim window reopened` | violet-500 | `by {actorName}`. No owner change |

Empty timeline is legitimate — the `AUTO_ASSIGNED` row is written best-effort in a `REQUIRES_NEW` transaction and can be missing. Copy: `No ownership history recorded for this lead.` Never an error.

All names are denormalised snapshots. Do not link them to user pages.

### Reassign (S6/S7, `LEAD_REASSIGN_LOCKED`)

Footer action `Reassign…` expands an inline panel:

- Owner picker sourced from `GET /api/leads/assignment/recommendation` → `eligibleUsers[]` (`{id, name, email, activeLeads}`), rendered as a `SearchableSelect` (already exported from `@features/leads`) with `{name}` primary and `{activeLeads} active` secondary — workload is the whole reason a manager is choosing manually.
- Optional `note` (max 255, counter at 200).
- Submit → `POST /{publicId}/reassign {assignedUserId, note}`. **No `expectedClaimVersion` exists on this DTO** — the call cannot lose a race, and two managers reassigning at once both succeed (last writer wins). The UI compensates by ordering incoming `lead-locked` events by `claimVersion` and dropping lower ones, so the owner label cannot flicker backwards.
- `409 NOT_LOCKED` → `This lead is still open — claim it instead.` and swap the panel's action for `Claim`.
- `400` `This user cannot be assigned leads: {name}` → inline under the picker.
- **`403` on the recommendation fetch is expected** for a user who holds `LEAD_REASSIGN_LOCKED` but not `LEAD_CREATE` (§10 gap 4). Degrade to: `Reassign is unavailable — your account cannot load the agent list. Ask an administrator.` Do not toast, do not retry.

### Reopen (S6 only, `LEAD_REASSIGN_LOCKED`)

Footer action `Reopen claim window`, shown **only** when `leadStage === "Contacted"`. Confirm inline (not a modal): `This puts the lead back in the claim pool and resets its stage to New Lead. The recorded first-response time is kept.` → `POST /{publicId}/reopen-claim {note}`.

The 409s here carry **no `details.claimLost`** — read `message` and render it inline. On success the lead reappears in everyone's queue via a `lead-alert` event tagged `Reopened`.

---

## 8. Annotated layout sketches

### 8a. Alert card (bottom-right stack)

```
                                            ┌ oldest card pinned to the corner;
                                            │ new cards insert ABOVE, nothing moves
 ┌──────────────────────────────────────────────────────┐
 │▍ JustDial          NEW LEAD          ⌥C          [×] │  ← 2px source rule; ⌥C hint on
 │                                                      │    the newest card only
 │  ┌────┐  Deepak Sharma            LD-26-0412         │
 │  │ DS │  Bali · 2 Adults, 1 Child · ₹1,85,000        │
 │  └────┘  +91 98204 41122                             │
 │                                                      │
 │  Auto-assigned to Priya · open until contacted       │  ← ownerName from the payload
 │                                                      │
 │  [ Claim ]                    [ View ]        4:38   │  ← live countdown, tabular-nums
 │▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁│  ← 10s progress, blue-600 on slate-200
 └──────────────────────────────────────────────────────┘
 ┌──────────────────────────────────────────────────────┐
 │▍ WhatsApp          NEW LEAD                      [×] │
 │  ┌────┐  Anita Rao                LD-26-0411         │
 │  │ AR │  Dubai · 2 Adults · ₹96,000                  │
 │  └────┘  +91 90040 77821                             │
 │  Auto-assigned to you · open until contacted         │
 │  [ Claim ]                    [ View ]        2:03   │
 └──────────────────────────────────────────────────────┘
   +4 more incoming · ⌥I
```

After `lead-claimed` arrives for a live card (someone else won, lead still open):

```
 │  Now with Rakesh · still open to claim               │  ← button re-armed with the
 │  [ Claim ]                    [ View ]        3:51   │    payload's bumped claimVersion,
                                                             card timer shortened to 3s
```

### 8b. Incoming queue — `/leads/incoming`

```
 Incoming leads                                    ● Live    🔊    ?
 ─────────────────────────────────────────────────────────────────────────────

 ┌ New today ────┐ ┌ Open to claim ┐ ┌ Avg 1st resp ─┐ ┌ Past target ──┐
 │ 27            │ │ 12            │ │ 4m            │ │ 3             │
 │ created today │ │ all time      │ │ contacted     │ │ among today's │
 │               │ │ not just today│ │ today · tgt 5m│ │ leads         │
 └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘

 [ Filter by name, phone, code…            /]   ( All │ Mine │ Past target )
 ─────────────────────────────────────────────────────────────────────────────
    CUSTOMER              SOURCE     ENQUIRY               VALUE   OWNER      SLA
 ─────────────────────────────────────────────────────────────────────────────
 ▍  Deepak Sharma         ● JustDial Bali · 2A 1C       ₹1,85,000  Priya     4:38   📞 ⋯ [ Claim ] [ Mark contacted ]
    LD-26-0412
 ─────────────────────────────────────────────────────────────────────────────
 ▍  Anita Rao             ● WhatsApp Dubai · 2A            ₹96,000  Yours     2:03   📞 ⋯          [ Mark contacted ]
    LD-26-0411                                                                              ← Claim hidden: self-claim is a no-op
 ─────────────────────────────────────────────────────────────────────────────
 ▍  Mohit Verma           ● Website  Singapore · 4A     ₹3,10,000  Rakesh   +2:14   📞 ⋯ [ Claim ] [ Mark contacted ]
    LD-26-0409                                                     ╰ red row tint, count-up
 ─────────────────────────────────────────────────────────────────────────────
 ▍  Sneha Patil  Reopened ● Referral Manali · 2A          ₹54,000  Priya    +18:42  📞 ⋯ [ Claim ] [ Mark contacted ]
    LD-26-0377                                            ╰ S9: negative countdown, NOT breached
 ─────────────────────────────────────────────────────────────────────────────
 ▍  Kiran Joshi           ● Meta Ads Goa · 2A 2C         ₹1,20,000  Rakesh    —     📞 ⋯
    LD-26-0405   Rakesh claimed this a moment ago.               [ Claim anyway ]
                 ╰ VERSION_STALE: amber, row stays, button re-armed from details.claimVersion
 ─────────────────────────────────────────────────────────────────────────────
    j/k move · c claim · m contacted · h history · Enter expand · / filter · ? shortcuts
```

### 8c. Lead-detail claim header — `/EditLead/:id`

Open (`openToClaim === true`, from `LeadResponseDto`):

```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │  Open to claim · anyone in your agency can take this until first contact │
 │  Owner Priya · New Lead · 3:12 to first response       [ Claim ]  [ History ] │
 └──────────────────────────────────────────────────────────────────────────┘
```

Locked (`openToClaim === false`):

```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │  Contacted · claim window closed                                          │
 │  Owner Priya · answered in 3m 20s        [ History ] [ Reassign ] [ Reopen ] │
 └──────────────────────────────────────────────────────────────────────────┘
                                            ╰ Reassign/Reopen only with LEAD_REASSIGN_LOCKED
                                              Reopen only while leadStage === "Contacted"
```

Note: `LeadResponseDto` has no `slaSecondsRemaining`, so the header's countdown is computed against the browser clock from `createdAt` + `slaTargetSeconds`. It is therefore shown to the **minute** (`~3m to first response`), never to the second — a second-precision number that disagrees with the queue's server-derived one is worse than a coarse one that doesn't.

---

## 9. Empty, error, offline

| Condition | Detection | Treatment |
|---|---|---|
| **Loading** | `loading === true` | 5 skeleton rows on the real grid template, `animate-pulse` on slate-100 blocks. Tiles render their frame with `—`. Never a spinner |
| **Empty** | `leads.length === 0`, `connected`, `stats.openToClaim === 0` | §4 empty state |
| **Empty but stats disagree** | `leads.length === 0 && stats.openToClaim > 0` | amber banner: `{n} leads are open to claim but none loaded. Refresh` + button. This is a real state after a failed background refresh (which deliberately does not blank the list) |
| **Truncated (S5)** | `leads.length >= 200 \|\| stats.openToClaim > leads.length` | amber banner: `Showing the 200 newest of {stats.openToClaim} open leads. Older ones are in All Leads.` The API exposes no truncation flag; this inference is the only detector |
| **SSE connecting** (first 15s of a drop) | `onError` fired, no `onOpen` yet | header pill → `Reconnecting…` (slate dot). No banner. Transient blips must not shout |
| **SSE down > 15s** | timer since last `onError` | amber strip above the list: `Live updates paused. Showing the list as of 14:32.` + `Refresh` button. The pill reads `Offline`. **Rows stay fully interactive** — claim still works over HTTP; only the push is gone |
| **Reconnected** | `onOpen` after a down period | fire `refresh()` immediately, then a 3s green strip: `Reconnected · list refreshed`. This is the backfill: SSE has no `Last-Event-ID` replay and the server buffers nothing, so every event in the gap is permanently lost and `GET /alerts/open` is the only recovery |
| **Reconciliation** | always | `refresh()` every **45s** while the queue page is visible, **120s** when only the host is mounted, and immediately on `visibilitychange → visible` after > 60s hidden. Required regardless of SSE health: LOST, CONVERTED and soft-delete transitions broadcast **nothing**, and `LeadAlertBroadcaster` swallows send failures silently. Today's flat 120s (`useLeadAlerts.jsx:36`) is too slow for a screen whose unit of value is seconds |
| **Feed fetch fails** | `getOpenLeads()` rejects | **do not blank the list** (current behaviour is correct). Show the stale-data strip: `Couldn't refresh — showing leads from 14:32.` The axios interceptor has already toasted anything actionable (401/403/429/500/network); do not toast again |
| **No `LEAD_READ`** | `canSee === false` | `<AccessDenied/>`. The route is already guarded, so this is defence-in-depth |
| **`MODULE_NOT_ENABLED`** | `code === "MODULE_NOT_ENABLED"` on any call | branch on `code`, not status. The interceptor's upgrade path handles it; the page renders the empty frame, not an error |
| **Sound blocked** | `AudioContext` throws | silent. Audio is decoration (current try/catch is correct) |
| **404 on any claim-window call** | — | never an error toast. Row fades with `This lead is no longer available.` (S13 and S16 are indistinguishable by design) |

Error-policy contract: `400/404/409` are **silent by design** in `authRealm.js` — the call site renders them. Every claim failure in §3 is a call-site render, not a toast. Use `isAlreadyReported(err)` before any `toast.error`, per the leads reference idiom.

---

## 10. What the backend does not expose that this design needs

Ranked by how much design compromise each one forces.

1. **No `serverTime` / `emittedAt` on `LeadAlertDto`.** `slaSecondsRemaining` is accurate only at the instant of receipt and cannot be re-based. After a laptop sleep the countdown is silently wrong. *Design cost:* a 45s reconciliation poll and a visibility-change re-fetch exist purely to paper over this. *Ask:* one `emittedAt` (or `serverNow`) field on the DTO.
2. **No `slaBreached` on `LeadAlertDto`.** Only the sign of `slaSecondsRemaining` is available, and after a reopen (S9) that sign is a lie — the countdown is deeply negative while the frozen `firstResponseSeconds` may have beaten the target. *Design cost:* the queue's filter has to be called `Past target` instead of `Breached`, and no row can honestly say "SLA missed". *Ask:* the same `slaBreached` boolean `LeadClaimResultDto` already carries.
3. **No way for the client to learn its own user `publicId`.** `LeadAlertDto.ownerPublicId` is a UUID; the FE has only `localStorage["userName"]`. Today's `isMine` is a **string name comparison** (`LeadAlerts.jsx:186`) — two staff sharing a display name both see `Yours`, and a missing key makes every row look unowned. The only source is `GET /api/leads/assignment/recommendation` → `self.id`, gated on `LEAD_CREATE`. *Interim design:* on provider mount, if `hasPermission(P.LEAD_CREATE)`, fetch it once and cache `self.id` as the identity for `Yours`/`Mine`; otherwise fall back to the name compare. *Ask:* a `publicId` claim on the staff JWT, or on `GET /api/permissions/me`.
4. **No eligible-owner endpoint gated on `LEAD_REASSIGN_LOCKED`.** The reassign picker's only source is `assignment/recommendation`, gated on `LEAD_CREATE`. A user holding reassign but not create gets a 403 fetching the dropdown for an action they are authorised to perform. *Design cost:* the graceful-degrade copy in §7. *Ask:* widen that endpoint's authority, or add `GET /api/leads/assignable-users` on `LEAD_READ`.
5. **No advance signal of assignable-pool membership (S15).** A `SUB_AGENT` granted `LEAD_CLAIM` sees the button and can only discover at click time that they can never own a lead. *Design cost:* a session-scoped `leadClaimIneligible` flag learned from a 403. *Ask:* a `canOwnLeads` boolean on `GET /api/permissions/me`.
6. **No truncation flag or total on `/alerts/open`.** Capped at 200, no pagination, no cursor; the server only logs a warning. Above 200 open leads, some are unreachable from this screen entirely. *Design cost:* the S5 banner is an inference from `stats.openToClaim > leads.length`. *Ask:* `total` + `truncated` on the response, or a cursor.
7. **No filtering or `since` param on `/alerts/open`.** Every reconnect re-downloads up to 200 full DTOs. On a busy tenant with a flaky connection this is the dominant network cost. *Ask:* `?since={emittedAt}`.
8. **Terminal and delete transitions broadcast nothing.** Marking an open lead LOST, converting it via the booking flow, and soft-deleting it all leave the row on every open screen. *Design cost:* the whole 45s reconciliation loop. *Ask:* a `lead-locked` broadcast on those three paths — the client already handles the event.
9. **SSE events carry no `id` and there is no `Last-Event-ID` replay.** A dropped connection permanently loses every event in the gap; the only durable trace is the `LEAD_CREATED` in-app notification, and that exists for creation only — never for claim, lock, reassign or reopen. *Design cost:* the entire §9 backfill design. *Ask:* event ids + a short replay buffer, or at minimum a persisted notification on claim.
10. **`previousOwnerName` / `actorName` are event-only.** They are null on rows fetched from `/alerts/open`, so after a reconnect the "Rakesh took this from Priya" context is gone. *Design cost:* that line renders only on live-updated rows, which reads as inconsistent. *Ask:* populate them on the feed response.
11. **`LeadResponseDto` lacks `slaSecondsRemaining`, `slaBreached`, and `firstContactedByName`/`firstContactedByPublicId`.** So the lead-detail header and the AllLeads SLA marker must compute against the browser clock (the exact thing `LeadAlertDto` refuses to do), and S8 — "contacted by Rakesh while owned by Priya" — cannot be rendered anywhere without a second call to `/assignment-history`. *Design cost:* minute-precision only in the detail header; history drawer required to explain S8.
12. **`LeadClaimResultDto` cannot re-seed the countdown.** It has `slaTargetSeconds` and a point-in-time `slaBreached` but no `createdAt` and no `slaSecondsRemaining`. After a successful claim the UI has to keep the `createdAt`/`receivedAt` it already had. Any surface that claims *without* having first seen the feed row (the lead-detail header) therefore cannot show a countdown after the claim. *Ask:* `slaSecondsRemaining` on the result DTO.
13. **`/reassign` has no `expectedClaimVersion`.** Two managers reassigning simultaneously both get 200 and both broadcast; last writer wins silently. *Design cost:* the client must order incoming `lead-locked` by `claimVersion` and drop lower ones purely to stop the owner label flickering.
14. **No unclaim / release endpoint.** An agent who claims by mistake has no way back except asking a manager to reassign — and reassign refuses an open lead (409 `NOT_LOCKED`). There is genuinely **no** undo for a mis-claim. *Ask:* `POST /{publicId}/release`.
15. **No bulk claim.** A burst of 8 leads is 8 round-trips, each running `AssignableUserResolver.resolve` over the whole tenant. *Ask:* if bursts are common, a batch endpoint; otherwise document the debounce requirement.
16. **No endpoint to read or set the SLA target.** `slaTargetSeconds` is per-lead and pinned at creation from a deployment property; there is no per-tenant override and no settings screen can exist. The tiles must read `stats.slaTargetSeconds` and the UI must never hardcode 300.
17. **Tenant-timezone vs JVM-timezone skew on the breach tile.** `/alerts/stats` computes `now` in the *tenant's* zone and compares it against `created_at`, written in the *JVM's* zone; per-row breach uses plain `LocalDateTime.now()`. On a UTC server with an IST tenant the tile and the rows drift by 5h30m — which would report effectively every open lead as breached while the rows still show time remaining. **Verify against the deployed TZ before shipping the `Past target` tile.** Not a design gap; a correctness risk the design cannot detect or hide.
18. **The SSE broadcast has no permission filter.** `pushToTenant` reaches every connected emitter, so `STAFF` with zero permissions and `ACCOUNTANT` without `LEAD_READ` receive `customerName`, `phone`, `value` and `destination` on the wire even though `GET /alerts/open` would refuse them. The provider gates rendering on `canSee`, but that is cosmetic — the data is already in the browser. Flag to the backend owner as a privacy issue; no frontend change can fix it.
19. **No `referenceType` that deep-links to the claim queue.** The `LEAD_CREATED` in-app notification carries `referenceType: "LEAD"`, which `NOTIF_ROUTE_MAP` sends to `/allleads` — where a claimable-but-out-of-scope lead (S4) does not appear. *Ask:* a distinct `referenceType` (or add the mapping client-side and accept that it also catches non-claim lead notifications).
20. **Nothing states whether a lead will be openable after claiming it (S4).** The answer is always yes — claiming makes the viewer the owner, so OWN scope then covers it — but no field says so, and the UI cannot pre-warn a user that the name they are about to click 404s *before* they claim. Handled by never linking the name from the queue; a `visibleToMe` boolean would let the queue link out normally.