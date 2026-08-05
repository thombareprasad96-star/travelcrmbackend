# Leads List (`/allleads`) — UI Audit, Redesign Spec & Backend Fixes

**Date:** 2026-08-04
**Frontend file under discussion:** `travelcrmfe/travelcrmfrontend/src/features/leads/pages/AllLeads.jsx`
**Backend files touched:** `lead/service/LeadLogServiceImpl.java`, `quotation/dto/QuotationRefDto.java`, `quotation/repository/QuotationRepository.java`, `quotation/analytics/QuotationWeblinkViewRepository.java`, `quotation/service/QuotationServiceImpl.java`

---

## 0. Status at the time of writing

| Workstream | State |
|---|---|
| **Audit of the leads list UI** | Done — findings in §2 |
| **Full redesign spec** | Written — §3. **Not implemented.** |
| **Demo slice built in `AllLeads.jsx`** (Next action column, Needs action tab, Lead ID merge, never-contacted fix) | Built, verified, then **REVERTED on owner's instruction**. Full source preserved in §4 so it can be re-applied verbatim. |
| **Backend fix 1** — log follow-up date now mirrors onto the lead | **KEPT / LIVE** — §5 |
| **Backend fix 2** — `QuotationRefDto` dead fields populated | **KEPT / LIVE** — §6 |
| Verification | `vite build` exit 0; `mvnw compile` exit 0; **503 backend tests, 0 failures, 0 errors** (PDF smoke test excluded on purpose) |
| Committed? | **No.** Nothing was committed — the owner commits manually in both repos. |

> **Why the frontend was reverted:** the owner asked for a *full* redesign rather than the
> incremental demo slice, then chose to reset the page to its previous state and keep only the
> backend corrections. The demo code is not lost — §4 is a complete, paste-ready record.

---

## 1. Scope and goal

The owner asked, in effect: *"as someone with 15+ years of experience, what should this screen
look like so it is genuinely easy to use?"*

The answer that framed everything below:

> A leads list's job is **not** to display lead data. It is to tell the agent **who to call next.**
> Today the screen displays sixteen columns of reference data and leaves the decision entirely to
> the human, on every single row, every single time.

Two supporting constraints from the owner's stated design north star
(`ui-north-star-notion-linear`, refined 2026-08-04):

- **House kit owns the chrome** — page gradient `from-slate-50 via-blue-50/30 to-slate-100`,
  glass panel (`bg-white/80 backdrop-blur-md rounded-2xl border-slate-200/60 shadow-sm`),
  `rounded-xl` controls / `rounded-2xl` cards, Plus Jakarta Sans set inline. A new screen must
  look like it belongs in this app.
- **North star owns the data surface inside it** — dense aligned rows on one grid template,
  tabular numerics, keyboard-first, restrained motion, **colour reserved for the exceptional**
  so the default state needs no badge.

---

## 2. Audit findings

All line numbers refer to `AllLeads.jsx` **as it stood before any change** (2024 lines).

### 2.1 The table is 2090px wide and cannot fit any laptop

`LEAD_COLUMNS` (`:143-161`) declares 16 columns with fixed widths:

| key | label | width |
|---|---|---|
| select | — | 44 |
| leadId | Lead ID | 132 |
| info | Lead Info | 208 |
| dest | Destination | 152 |
| travel | Travelers Info | 176 |
| services | Services | 96 |
| quote | Quotation | 160 |
| booking | Booking | 122 |
| weblink | Weblink | 126 |
| logging | Logging | 94 |
| assigned | Assigned To | 150 |
| amount | Amount | 132 |
| margin | Margin | 120 |
| type | Type | 128 |
| stage | Stage | 138 |
| actions | Actions | 112 |

`LEAD_TABLE_MIN_W` = **2090px**. On a 1440px laptop minus the app sidebar (~250px) the viewport is
about 1150px, so roughly **940px of the table is always off-screen** — including Stage and Actions,
the two columns an agent touches most. Every row interaction begins with a horizontal scroll.

### 2.2 Only 100 leads are loaded, and every filter is client-side

- `leadService.getAllLeads(page = 0, size = 100)` (`leadService.js:267`) — one call, page 0, on mount only.
- `filteredLeads` (`:1625-1664`) is a `useMemo` over the in-memory array: search, date filter and tab
  are all client-side predicates.
- The stat cards (`stats`, `:1612-1622`), the "N results" pill and every tab count derive from the
  same 100-row array.

**Consequence: past 100 leads, search, filters, tab counts and the conversion/win-rate figures are
all wrong** — and nothing on screen says so. This is the single largest correctness problem on the page.

Backend confirms there is no server-side alternative today: `GET /api/leads`
(`LeadController.java:78-93`) accepts only `page`, `size`, `sortBy`, `sortDir`.
`GET /api/leads/search?keyword=` (`:60`) returns a **single** `LeadResponseDto`, not a list — it is a
lookup, not a list search.

### 2.3 Eight stages exist; only three can be filtered

Backend `LeadStage` has 8 values: `New Lead, Contacted, Follow Up, Qualified, Proposal Sent,
Converted, Reopened, Lost`.

The tab row (`:1908-1922`) offers exactly four buttons: `All`, `Fresh` (a *type*, not a stage),
`New Lead`, `Contacted`. There is **no stage dropdown anywhere on the page**, so
Follow Up / Qualified / Proposal Sent / Converted / Reopened / Lost cannot be filtered at all.

Also missing, and these are the two views a sales floor actually lives in:
**"assigned to me"** and **"follow-ups due today"**.

### 2.4 Changing a stage PUTs the entire lead

`handleStageChange` (`:1525-1552`) builds `{...leadToUpdate, leadStage: newStage}` and calls
`leadService.updateLead(...)` → `PUT /leads/{publicId}` with the full payload including
`services` and `itinerary`.

The backend already exposes the right endpoint:

```java
/** Drag-and-drop: move a lead to a new stage without sending the full lead payload. */
@PatchMapping("/{publicId}/stage")
@PreAuthorize("hasAuthority('LEAD_UPDATE')")
public ResponseEntity<ApiResponse<LeadResponseDto>> updateLeadStage(...)
```
`LeadController.java:104-115`

Three problems with the PUT: it is a large request for a one-field change; it **last-write-wins over
any concurrent edit** to the rest of the lead; and the UI does not update until the response lands,
so the dropdown visibly lags with no pending state.

### 2.5 Filters live in component state, not the URL

No `useSearchParams` anywhere in the file. Consequences:

- A filtered list cannot be bookmarked or shared.
- Opening a lead (`handleEditNavigate` → `navigate('/EditLead/:id')`) and pressing Back resets
  search, date filter, tab, page index and scroll position.

For a screen an operator returns to fifty times a day, this is the most-felt papercut after §2.1.

### 2.6 Row selection exists but does nothing

Every row has a checkbox (`:594-600`), the header has a select-all (`:1947-1951`), and the selection
strip (`:1928-1933`) renders exactly:

```
N selected   ·   Clear
```

There is no assign, no bulk stage change, no export, no bulk delete. The column costs 44px plus the
cognitive weight of an affordance that leads nowhere.

### 2.7 No keyboard support at all

`grep` for `Escape` / `keydown` / `onKeyDown` in the file returns **nothing**. No `/` to focus
search, no `j`/`k`, no `Esc` to close a modal (they close only on backdrop click or the × button),
no command palette. This is in direct tension with the owner's stated keyboard-first north star.

### 2.8 Colour is applied everywhere, so it signals nothing

| Source | Line | What it colours |
|---|---|---|
| `colorForIndex(index)` | `:49`, used at `:595` | Row left border and avatar, from `index % 6` |
| `AVATAR_GRADIENTS` | `:39-46` | 6 avatar gradients, also by index |
| `STAGE_PILL` | `:54-63` | 8 stage colours |
| `TYPE_PILL` | `:75-80` | 4 type colours |
| `SERVICE_COLORS` | `:93-102` | 8 pastel service chips |
| Travelers cell | `:654-668` | 3 differently-coloured pills **per row** (slate / amber / emerald) |
| Table header | `:1943` | Solid `bg-blue-600` uppercase white |

The index-keyed colours are the worst offender: they **change when you sort**, they mean nothing,
and they look exactly like colours that mean something. The net effect is that nothing on the screen
stands out, because everything is coloured.

### 2.9 Two columns can never display data

`QuotationRefDto` — the object embedded on each lead as `latestQuotation` — carried only two fields:

```java
private UUID publicId;
private BigDecimal grandTotal;
```

But `LeadRow` reads:

```js
const marginVal   = q?.margin ?? q?.marginAmount ?? lead.margin ?? null;   // always null
const weblinkViews = q?.viewCount ?? q?.weblinkViews ?? q?.views ?? 0;     // always 0
q?.version                                                                 // always undefined
q.templateStyle                                                            // always undefined
```

`LeadResponseDto` has no `margin` field either. Therefore:

- the **Margin column (120px) renders `—` on every row, forever**;
- the **weblink view badge (126px column) reads `0` on every row, forever**;
- the share text says `Travel Quotation` with a blank version;
- the weblink design comparison `style !== (q.templateStyle || 'CLASSIC')` always compares against
  `'CLASSIC'`, so opening a weblink in MODERN/PREMIUM re-PATCHed the style every single time.

**Fixed — see §6.**

### 2.10 No mobile layout

`grep` for `md:hidden` / `sm:hidden` / `lg:hidden` finds one match, and it is the header count pill.
There is no card fallback: on a phone the page is a 2090px horizontal scroll.

(CLAUDE.md describes duplicated desktop/mobile row markup at `:492`/`:560`. That is stale — the
current file renders a single flat table with no responsive branch.)

### 2.11 Smaller items

- **Money format** — `fmtAmountINR` (`:136`) renders `₹3,10,000.00` with paise, in a 132px
  right-aligned column, without `tabular-nums`, so digits do not align vertically between rows.
- **No sortable headers** — `sortOrder` is `useState('desc')` with **no setter ever used** (`:1446`);
  headers are not clickable; Amount, Margin and Travel Date cannot be sorted.
- **Row entry animation** — every row carries `animation: fadeUp .35s ease both` with
  `animationDelay: index * 30ms` (`:592`), so a page change cascades for up to 300ms.
- **Stat cards** — 4 gradient cards, each with a 60-step `setInterval` count-up, hover-translate and
  3 decorative blur circles (`:248-284`). Collapsed by default, which helps, but the numbers they
  show are the §2.2 numbers — i.e. wrong past 100 leads.
- **Duplicate counts** — "N total" (`:1768`) and "N results" (`:1852`) are two violet gradient pills
  saying nearly the same thing.
- **Density** — `py-2.5` cells with 3-line Lead Info and a 3-pill Travelers cell give ~64-72px rows;
  default page size is 10, so roughly ten rows are visible per screen.
- **Accessibility** — icon-only buttons carry `title` but no `aria-label`; the header select-all
  checkbox has neither; the Stage/Type `<select>`s use `appearance-none` + `outline-none` with no
  focus-visible replacement, so they neither look like controls nor show focus.
- **Dead state** — `previewPickFor` in `QuotationsModal` is only ever cleared, never set.
- **Unused backend capability** — `GET /api/leads/board` (`LeadController.java:98`) returns leads
  grouped into pipeline columns and is documented as powering "LeadKanban.jsx". No such component
  exists in the frontend. A Kanban view is available for free.

---

## 3. Target design (specified, not built)

### 3.1 Columns: 16 → 8, plus a right-side drawer

```
☐ │ Lead              │ Next action         │ Trip          │ Pax  │ Value     │ Owner  │ Stage      │ ⋯
──┼───────────────────┼─────────────────────┼───────────────┼──────┼───────────┼────────┼────────────┼───
☐ │ Priya Sharma      │ 🔴 Follow-up 2d late│ Bali · 6N     │ 2A1C │ ₹3,10,000 │ Rohit  │ Proposal ▾ │ ⋯
  │ LD-26-0041        │    Added 12 Sep     │ 12 Sep        │      │ +₹42,000  │        │            │
```

Proposed widths: `select 40 · Lead 240 · Next action 180 · Trip 190 · Pax 96 · Value 130 ·
Owner 140 · Stage 132 · ⋯ 52` = **~1200px**, which fits a 1366 laptop.

Everything removed from the row — services, quotation buttons, weblink, logs, convert, type —
moves into a **right-side `LeadDrawer`** opened by clicking the row. The drawer replaces
`ViewLeadModal` and does **not navigate**, so list state, filters and scroll survive.

Drawer contents (one scrollable panel, section headings rather than tabs):
header (name, code, stage, type, Edit) → quick actions (WhatsApp, call, email, add log,
convert/booked) → overview (contact, travellers, departure, travel date, budget, source, owner,
created) → itinerary → services → latest quotation card (view / new / weblink + views / suggest
packages) → activity (log count → `LogsModal`).

### 3.2 The "Next action" column — the core idea

Per row, compute a single verdict and **sort on it by default**:

| Condition | Label | Tone |
|---|---|---|
| Follow-up date in the past | `Follow-up 2d overdue` | red |
| Follow-up date today | `Follow-up today` | amber |
| Unclaimed, SLA clock running | `Unclaimed · 12m left` / `Unclaimed · SLA missed` | amber / red |
| Never contacted, ≥1 day old | `Never contacted · 3d` (red at ≥3d) | amber / red |
| Travel date within 14 days | `Travels in 6d` | amber |
| Quotation sent | `Quote sent · chase reply` | grey + blue dot |
| Qualified, no quotation | `Send quotation` | grey |
| Contacted but no log | `Contacted · no log yet` | grey |
| In Follow Up with no date set | `Set a follow-up date` | grey |
| Converted / Lost | `Booked` / `Closed` | muted |

**Colour rule: only red and amber get colour.** Everything else stays grey, so a coloured row on
this page always means the same thing — someone is waiting on us.

Implementation is in §4.1 and needs no backend change: every input already ships on the list
response.

### 3.3 Views instead of stage tabs

Chips: `Needs action` · `All` · `Mine` · `Unassigned` · `Converted`
plus a real filter bar: **Stage (multi-select, all 8)** · Type · Owner · Source · Destination · Date.

`Mine` and `Owner` source their user list from `leadAlertService.getAssignmentPool()`
(`GET /leads/assignment/recommendation`), which is also the only place the client can learn its own
`publicId` (`self.id`). Note the endpoint is gated on `LEAD_CREATE` while `/reassign` is gated on
`LEAD_REASSIGN_LOCKED`, so a 403 here must degrade quietly rather than be treated as a fault.

### 3.4 URL as the source of truth

`?view=needs-action&q=priya&stage=Qualified,Proposal+Sent&type=Hot&owner=<uuid>&date=last_7_days&from=&to=&page=0&size=25&sort=action&dir=asc`

Bookmarkable, shareable, and survives Edit-and-Back.

### 3.5 Speed

- **Keyboard:** `/` focus search · `j`/`k` move active row · `Enter` open drawer · `e` edit ·
  `l` add log · `x` toggle selection · `Esc` close · `?` shortcut sheet. All suppressed while
  typing in a field or while a modal is open.
- **Stage change:** `PATCH /leads/{publicId}/stage`, applied optimistically, rolled back on
  failure, with an **Undo** action on the success toast.
- **Bulk actions:** Assign to (`leadAlertService.reassign`) · Set stage (PATCH) · Export CSV
  (client-side) · Delete (confirmed). Per-row failures reported, never swallowed.
- **Density toggle** compact (44px) / comfortable, persisted in `localStorage['leads:density']`;
  default page size **25**, not 10.

### 3.6 Visual restraint

- Delete `AVATAR_GRADIENTS`, `ACCENT_SOLIDS`, `colorForIndex`; avatar becomes neutral slate.
- Type demoted from a full pill column to a small coloured dot before the name (Hot red / Warm
  amber / Fresh blue / Cold slate), with editing moved into the drawer.
- Header `bg-slate-50 text-slate-500 text-[11px]` with a bottom border — the solid `bg-blue-600`
  header competes with blue links and blue buttons and destroys hierarchy.
- Travelers cell's three pills become plain text.
- Money: `tabular-nums`, no paise in the list (`₹3,10,000`); paise stay in detail views.
- Drop the per-row `fadeUp` cascade; keep a single ~120ms fade on the table body when data swaps.
- Sticky table header.
- Replace the 4 gradient stat cards with a thin, clickable-as-filter KPI strip.

### 3.7 Mobile

Below `lg`, render cards instead of a horizontally-scrolling table: name + code, Next action,
trip, value, stage pill, and one `⋯`. Tap opens the drawer.

### 3.8 Honesty about the 100-lead cap

Until §2.2 is fixed server-side, show a notice whenever the fetch returns exactly the page size:
*"Showing the first 100 leads — filters and counts apply to these only."*

### 3.9 Recommended order

**No backend needed:** columns → drawer · URL filters · PATCH stage + undo · all 8 stage filters ·
bulk actions · keyboard · money format · colour cleanup · density · mobile cards.

**Backend needed:** server-side `search / stage / type / owner / source / date / followUpDue`
filtering with paging on `GET /api/leads`, plus `lastContactedAt` on the list DTO.

---

## 4. The demo slice — built, verified, then reverted

This section is a **complete record** of what was added to `AllLeads.jsx`. It compiled
(`vite build` exit 0) and behaved correctly. It was reverted at the owner's request. To re-apply,
paste these blocks back at the marked anchors.

### 4.1 `nextAction()` and its helpers

**Anchor:** immediately before `/* ─── TABLE LAYOUT ─── */`.

```js
/* ─── NEXT ACTION ─────────────────────────────────────
   The column that answers the only question this screen exists to answer: who do I
   call next. Everything else here is reference data the agent has to read and judge;
   this is a verdict.

   Computed entirely from fields the list response already carries — followUpDate,
   logCount, createdAt, travelDate, leadStage, the claim/SLA window and whether a
   quotation exists — so it costs no extra request and no backend change.

   First match wins: the order of the blocks below IS the priority order. Only
   'late' and 'due' are allowed colour. Everything else stays grey on purpose — a
   red row on this page must always mean the same thing (someone is waiting on us),
   or colour stops carrying information, which is what happened to the six random
   accent borders and the three pills already in every row.                        */

const DAY_MS = 86400000;
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d; };
/* "2026-09-12" is a LocalDate, not an instant — new Date() reads it as UTC midnight
   and lands on the previous day in any negative-offset zone. Split it instead. */
const parseDateOnly = (s) => {
  if (!s) return null;
  const [y, m, d] = String(s).slice(0, 10).split('-').map(Number);
  return (y && m && d) ? new Date(y, m - 1, d) : null;
};
const daysBetween = (later, earlier) => Math.round((later - earlier) / DAY_MS);

/* Stages a lead cannot be in unless a human already spoke to the customer. Converted and Lost
   are absent because nextAction returns before it needs them. Kept as display strings because
   that is the wire format — LeadStage serialises via @JsonValue displayName, never NEW_LEAD. */
const CONTACTED_STAGES = new Set(['Contacted', 'Follow Up', 'Qualified', 'Proposal Sent', 'Reopened']);

function nextAction(lead, now = Date.now()) {
  const today = startOfToday();
  const stage = lead.leadStage;

  // Closed either way — nothing is owed to this lead, so it must not compete for attention.
  if (stage === 'Converted' || lead.convertedBookingPublicId) return { tone: 'done', label: 'Booked' };
  if (stage === 'Lost') return { tone: 'none', label: 'Closed' };

  // 1. An explicit promise to the customer outranks every heuristic below it.
  const due = parseDateOnly(lead.followUpDate);
  if (due) {
    const late = daysBetween(today, due);
    if (late > 0) return { tone: 'late', label: `Follow-up ${late}d overdue` };
    if (late === 0) return { tone: 'due', label: 'Follow-up today' };
    if (late === -1) return { tone: 'soon', label: 'Follow-up tomorrow' };
  }

  // 2. Unclaimed lead with the first-response clock still running.
  if (lead.openToClaim && lead.createdAt) {
    const leftMs = (lead.slaTargetSeconds || 0) * 1000 - (now - new Date(lead.createdAt).getTime());
    return leftMs <= 0
      ? { tone: 'late', label: 'Unclaimed · SLA missed' }
      : { tone: 'due', label: `Unclaimed · ${Math.ceil(leftMs / 60000)}m left` };
  }

  // 3. Never contacted. One day of silence is normal, three is a leak.
  //    "Never contacted" has to mean exactly that. Log count alone is not evidence: an agent
  //    who calls a customer and then flips the Stage dropdown to Contacted has contacted them,
  //    logged or not — and a lead sitting in Contacted that keeps getting called never-contacted
  //    is how a column like this stops being believed. Three independent signals count as
  //    contact, any one of them is enough: the server's own firstContactedAt stamp, an activity
  //    log, or a stage the lead could only have reached by someone speaking to them.
  const everContacted =
    !!lead.firstContactedAt ||
    (lead.logCount || 0) > 0 ||
    CONTACTED_STAGES.has(stage);

  const created = lead.createdAt ? new Date(lead.createdAt) : null;
  const ageDays = created ? daysBetween(today, new Date(created.getFullYear(), created.getMonth(), created.getDate())) : 0;
  if (!everContacted && ageDays >= 1) {
    return { tone: ageDays >= 3 ? 'late' : 'due', label: `Never contacted · ${ageDays}d` };
  }

  // 4. Departure closing in while the lead is still open.
  const travel = parseDateOnly(lead.travelDate);
  if (travel) {
    const inDays = daysBetween(travel, today);
    if (inDays === 0) return { tone: 'due', label: 'Travels today' };
    if (inDays > 0 && inDays <= 14) return { tone: 'due', label: `Travels in ${inDays}d` };
  }

  // 5. Pipeline nudges — a hint, never a shout.
  if (lead.latestQuotation?.publicId) return { tone: 'hint', label: 'Quote sent · chase reply' };
  if (stage === 'Qualified') return { tone: 'hint', label: 'Send quotation' };
  // The hygiene case rule 3 deliberately stopped shouting about: contact happened, but there is
  // no record of what was said. Worth a nudge, not a red row.
  if (!(lead.logCount > 0)) return { tone: 'hint', label: 'Contacted · no log yet' };
  // A lead parked in Follow Up with no date on it will never surface in rule 1 — say so.
  if (stage === 'Follow Up' && !due) return { tone: 'hint', label: 'Set a follow-up date' };
  if (stage === 'New Lead' || stage === 'Contacted') return { tone: 'hint', label: 'Qualify this lead' };

  // 6. A follow-up genuinely in the future needs nothing today — state it and stay quiet.
  if (due) return { tone: 'none', label: `Follow-up ${due.toLocaleDateString('en-US', { day: 'numeric', month: 'short' })}` };

  return { tone: 'none', label: '—' };
}

const ACTION_TONE = {
  late: { dot: 'bg-red-500',     text: 'text-red-700',    wrap: 'bg-red-50 border-red-200' },
  due:  { dot: 'bg-amber-500',   text: 'text-amber-800',  wrap: 'bg-amber-50 border-amber-200' },
  soon: { dot: 'bg-slate-400',   text: 'text-slate-600',  wrap: 'bg-white border-slate-200' },
  hint: { dot: 'bg-blue-400',    text: 'text-slate-500',  wrap: 'bg-transparent border-transparent' },
  done: { dot: 'bg-emerald-500', text: 'text-slate-400',  wrap: 'bg-transparent border-transparent' },
  none: { dot: 'bg-slate-200',   text: 'text-slate-400',  wrap: 'bg-transparent border-transparent' },
};

/* The two tones that mean a human is waiting — what the "Needs action" tab filters on. */
const NEEDS_ACTION_TONES = new Set(['late', 'due']);
```

### 4.2 Column changes

`leadId` was folded into `info`, and `action` inserted second:

```js
  { key: 'select', label: '', width: 44, align: 'center' },
  /* One person, one column. This was two — a 132px "Lead ID" holding a code and a
     created date, sitting next to a 208px "Lead Info" holding the same person's name.
     The code now rides on the name line and the created date moved into Next action,
     where a lead's age is the thing that makes it urgent. */
  { key: 'info', label: 'Lead', width: 232 },
  /* Placed second on purpose: after identity, the very next thing an agent should read
     is what they owe this lead — not the destination. */
  { key: 'action', label: 'Next action', width: 172 },
  { key: 'dest', label: 'Destination', width: 152 },
```

Net table width: 2090 → **2162px** (+72px, +3%). Honest note recorded at the time: this is a
*content* demo; the width fix comes with the full 8-column pass in §3.1.

### 4.3 `LeadRow` changes

Derived values, added next to the existing `travelStr` / `createdStr`:

```js
  const action = nextAction(lead);
  const tone = ACTION_TONE[action.tone] || ACTION_TONE.none;
```

The `Lead ID` `<td>` was deleted and its code merged into the identity cell:

```jsx
      {/* ── Lead (identity: code + name + how to reach them) ── */}
      <td className={TD}>
        <div className="flex items-center gap-2.5 min-w-0">
          <div className={`w-9 h-9 rounded-full bg-gradient-to-br ${avatar} flex items-center justify-center text-white text-xs font-extrabold shadow-sm flex-shrink-0`}>{initial}</div>
          <div className="min-w-0">
            {/* Name leads, code follows it — the code is what a customer quotes back on the
                phone, so it has to be visible, but it is never what the agent scans for. */}
            <div className="flex items-baseline gap-1.5 min-w-0">
              <button onClick={() => onView(lead)}
                className="text-sm font-bold text-blue-600 hover:text-blue-700 capitalize truncate text-left min-w-0">
                {name}
              </button>
              <span className="text-[10px] font-bold text-slate-400 font-mono flex-shrink-0" title={lead.publicId || lead.id}>
                {displayCode}
              </span>
            </div>
            <PhoneLink … />
            {lead.email && ( … )}
          </div>
        </div>
      </td>
```

The new cell, inserted directly after it:

```jsx
      {/* ── Next action ── */}
      {/* The verdict, not the data. Red/amber only when someone is actually waiting;
          the created date sits underneath because age is what makes it urgent. */}
      <td className={TD}>
        <div className={`inline-flex items-start gap-1.5 px-2 py-1 rounded-lg border max-w-full ${tone.wrap}`}>
          <span className={`w-1.5 h-1.5 rounded-full mt-[5px] flex-shrink-0 ${tone.dot}`} />
          <span className="min-w-0">
            <span className={`block text-[11px] font-bold leading-tight truncate ${tone.text}`} title={action.label}>
              {action.label}
            </span>
            <span className="block text-[10px] text-slate-400 font-medium mt-0.5">
              {createdStr ? `Added ${createdStr}` : '—'}
            </span>
          </span>
        </div>
      </td>
```

### 4.4 The "Needs action" work-queue tab

Filter branch inside `filteredLeads`:

```js
      let matchesTab = true;
      if (activeTab === 'Needs action') {
        // The work queue: every lead whose Next action came back red or amber.
        matchesTab = NEEDS_ACTION_TONES.has(nextAction(lead).tone);
      } else if (activeTab === 'Fresh') {
        matchesTab = lead.leadType === 'Fresh';
      } else if (activeTab !== 'All') {
        matchesTab = lead.leadStage === activeTab;
      }
```

Count and chip, in the tabs row:

```js
const needsActionCount = safeLeads.filter(l => NEEDS_ACTION_TONES.has(nextAction(l).tone)).length;
```

```jsx
{/* The work queue sits first — it is the only tab that answers "what now?".
    The others are ways of slicing the archive. */}
<button onClick={() => setActiveTab('Needs action')} className={btnClass('Needs action')}>
  <div className={`w-2.5 h-2.5 rounded-full ${needsActionCount ? 'bg-red-500 shadow-sm shadow-red-500/50' : 'bg-slate-300'}`} /> Needs action
  <span className={badgeClass('Needs action')}>{needsActionCount}</span>
</button>
```

### 4.5 Bug found during the demo, and its fix

The owner asked: *"stage Contacted hone ke baad bhi 'never contacted' aata hai kya?"* — and was right.

**The defect.** Rule 3 originally read `if (!lead.logCount && ageDays >= 1)`. An agent who phones a
customer and then flips the Stage dropdown to `Contacted` without writing a log left `logCount` at
0, so the row kept showing a red **`Never contacted · 3d`** on a lead that had plainly been
contacted. A verdict column that lies once loses the agent's trust permanently.

**The fix** — three independent signals, any one sufficient (`everContacted` in §4.1):

1. `lead.firstContactedAt` — the server's own stamp, written by
   `LeadRepository.markContacted…` JPQL (`LeadRepository.java:344`) and mapped onto the list
   response at `LeadMapper.java:219`. Most authoritative.
2. `lead.logCount > 0`.
3. `CONTACTED_STAGES.has(stage)` — a stage the lead could only have reached by someone speaking
   to the customer.

**Two blind spots closed at the same time**, both as quiet `hint` tones rather than red rows:

| Case | Before | After |
|---|---|---|
| Contacted, no log written | 🔴 `Never contacted · 3d` (wrong) | ⚪ `Contacted · no log yet` |
| Stage `Follow Up`, no date set | `—` (never surfaced anywhere) | ⚪ `Set a follow-up date` |

---

## 5. Backend fix 1 — a log's follow-up date now reaches the lead

**File:** `lead/service/LeadLogServiceImpl.java` — **KEPT / LIVE**

### The defect

`Lead.followUpDate` was written only by `LeadMapper:80` from the **create/update lead request**.
`LeadLogServiceImpl` never called `lead.setFollowUpDate(...)`, so a follow-up an agent agreed on a
call and recorded through *Add Log* was stored on the `LeadLog` row **only**.

Consequences: the lead list could not show it, no follow-up query could find it, and the Next action
column's highest-priority rule (`Follow-up Nd overdue`) almost never fired.

### The fix — `addLog`

```java
LeadLog saved = leadLogRepository.save(logEntry);
log.info("Lead log added | lead: {} | logId: {}", lead.getPublicId(), saved.getPublicId());

// Mirror the promise onto the lead itself. The log is the history of what was said; the
// lead's followUpDate is the single answer to "when do we next owe this customer a call",
// and it is the only one the leads list and any follow-up query can read without joining
// every log. Before this, a follow-up agreed on a call lived on the log alone and nothing
// outside the log modal ever knew about it.
//
// Newest promise wins, and a log WITHOUT a date leaves the field alone — a plain note must
// never silently cancel a follow-up somebody committed to. Dirty checking flushes it; the
// lead came from requireVisible() inside this @Transactional method, so it is managed.
if (request.getFollowUpDate() != null) {
    lead.setFollowUpDate(request.getFollowUpDate());
}
```

Design points:
- **Newest promise wins** — the field answers "when next", not "what was promised historically".
- **A log without a date does not clear the field** — a plain note must never silently cancel a
  follow-up somebody committed to.
- **No repository call** — `lead` comes from `leadAccessGuard.requireVisible(...)` inside a
  `@Transactional` method, so it is a managed entity and dirty checking flushes the change.

### The fix — `deleteLog`

The mirror has to survive a delete, or the lead advertises a follow-up nobody promised any more and
the list shows it as overdue forever.

```java
logEntry.softDelete(currentUserEmail());
leadLogRepository.save(logEntry);
log.info("Lead log deleted | lead: {} | logId: {}", lead.getPublicId(), logPublicId);

// The mirror addLog() maintains has to survive a delete. If the log just removed is the one
// the lead's followUpDate came from, re-point it at the newest surviving log that carries a
// date — or clear it when none is left. Skipping this would leave the lead advertising a
// follow-up nobody promised any more, and the leads list would show it as overdue forever.
// The derived query below forces a flush first, so the row just soft-deleted is excluded.
if (logEntry.getFollowUpDate() != null
        && logEntry.getFollowUpDate().equals(lead.getFollowUpDate())) {
    LocalDate surviving = leadLogRepository
            .findByLead_IdAndDeletedAtIsNullOrderByCreatedAtDesc(lead.getId())
            .stream()
            .map(LeadLog::getFollowUpDate)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    lead.setFollowUpDate(surviving);
}
```

Notes:
- Reuses the existing `findByLead_IdAndDeletedAtIsNullOrderByCreatedAtDesc` — no new repository
  method. Spring Data flushes before the derived query, so the just-soft-deleted row is excluded.
- Only recomputes when the deleted log **was** the source (`equals` on the date), so an unrelated
  delete is free.
- Added import: `java.util.Objects`.

---

## 6. Backend fix 2 — `QuotationRefDto`'s dead fields

**Files:** `quotation/dto/QuotationRefDto.java`, `quotation/repository/QuotationRepository.java`,
`quotation/analytics/QuotationWeblinkViewRepository.java`, `quotation/service/QuotationServiceImpl.java`
— **KEPT / LIVE**

### 6.1 New DTO fields

```java
/** Version label ("v1.0") — the lead list puts it in the WhatsApp/email share text. */
private String version;

/**
 * The design the customer's share link currently renders. The lead row's Weblink button
 * persists a style before opening, and it can only tell whether that PATCH is needed if it
 * knows the stored value — without this every open re-saved the style it already had.
 */
private TemplateStyle templateStyle;

/**
 * The agent's markup on this quotation — the only profit figure a quotation carries, since
 * there is no vendor-cost column to subtract. Named for what the lead list's column has always
 * been called; it is markup, not a computed revenue-minus-cost margin. Absent (not zero) when
 * no markup was ever entered, so the column can show "—" rather than a page of ₹0.00.
 */
private BigDecimal margin;

/**
 * How many times an actual client opened the share link. EXTERNAL views only — the tenant's own
 * staff opening their own quotation (ViewerType.HOME) is not customer interest and must not
 * inflate the badge the agent reads as "they looked at it".
 */
private Long viewCount;
```

`QuotationRefDto` is `@JsonInclude(NON_NULL)`, so `margin` is **omitted** when the `markup` column
was never set — the UI shows `—`. A markup explicitly entered as `0` still serialises as `0`. Those
are two different facts and the DTO keeps them distinct.

### 6.2 Projection and query

`QuotationRepository.LatestQuotationRef` gained two accessors and the JPQL two columns:

```java
        BigDecimal getTax();
        BigDecimal getMarkup();
        /** Version label and stored design — the lead row shares and opens the weblink from these. */
        String getVersion();
        TemplateStyle getTemplateStyle();
```

```sql
                   q.discount AS discount, q.discountType AS discountType,
                   q.tax AS tax, q.markup AS markup,
                   q.version AS version, q.templateStyle AS templateStyle
```

Import added: `com.crm.travelcrm.quotation.enums.TemplateStyle`.

### 6.3 Batched view tally

`QuotationWeblinkView` stores **one row per viewer IP** with its own `viewCount`, so a total must be
`SUM`med, not counted. New query on `QuotationWeblinkViewRepository`:

```java
@Query("""
        SELECT v.quotationPublicId AS quotationPublicId, SUM(v.viewCount) AS views
          FROM QuotationWeblinkView v
         WHERE v.quotationPublicId IN :ids
           AND v.tenantId = :tenantId
           AND v.viewerType = :viewerType
         GROUP BY v.quotationPublicId
        """)
List<QuotationViewTally> tallyViews(@Param("ids") Collection<UUID> ids,
                                    @Param("tenantId") Long tenantId,
                                    @Param("viewerType") ViewerType viewerType);

/** Closed projection for {@link #tallyViews}. */
interface QuotationViewTally {
    UUID getQuotationPublicId();
    Long getViews();
}
```

- **Explicitly tenant-scoped** — this entity is not Hibernate-filtered, because the public write
  path runs with no `TenantContext`.
- **`viewerType` is a parameter, not a hard-coded `EXTERNAL`**, so the caller states which audience
  it is asking about. The lead-list badge means "the client opened it", which is a different
  question from the analytics modal's total.

### 6.4 Service wiring

In `QuotationServiceImpl.getLatestRefsByLeads(...)`, each ref gained three fields:

```java
.version(row.getVersion())
.templateStyle(row.getTemplateStyle())
// Raw column, not the computed markup amount: computeTotals nulls-to-zero,
// and "no markup entered" has to stay absent so the lead list can show a
// dash instead of a page of ₹0.00.
.margin(row.getMarkup())
```

and the tally runs once for the whole page, after the latest-per-lead reduction:

```java
// View tallies in ONE extra query for the whole page — never one per row. Only for the
// quotations that actually survived the latest-per-lead reduction above.
if (!result.isEmpty()) {
    Set<UUID> chosen = result.values().stream()
            .map(QuotationRefDto::getPublicId)
            .collect(Collectors.toSet());
    Map<UUID, Long> views = weblinkViewRepository
            .tallyViews(chosen, tenantId, ViewerType.EXTERNAL)
            .stream()
            .collect(Collectors.toMap(
                    QuotationWeblinkViewRepository.QuotationViewTally::getQuotationPublicId,
                    QuotationWeblinkViewRepository.QuotationViewTally::getViews));
    // Explicit 0 rather than null: "nobody has opened it" is an answer the agent needs,
    // and it must not read as "we don't know".
    result.values().forEach(ref -> ref.setViewCount(views.getOrDefault(ref.getPublicId(), 0L)));
}
```

New field on the service: `private final QuotationWeblinkViewRepository weblinkViewRepository;`
New imports: `QuotationWeblinkViewRepository`, `ViewerType`, `java.util.Set`.

### 6.5 Effects, including two the frontend gets for free

- **Margin column** now shows the markup instead of a permanent `—`.
- **Weblink badge** now shows real external view counts instead of a permanent `0`.
- **Share text** now carries the version.
- **`openLeadWeblinkWithStyle`** compares `style !== (q.templateStyle || 'CLASSIC')` against the
  real stored style, so opening a weblink in an already-saved design no longer fires a redundant
  `setTemplateStyle` PATCH.
- **`QuotationStyleModal savedStyle={…}`** now receives the true saved design and can pre-select it.

**No schema change.** Every field already existed in the database; they simply never reached the
wire. No migration and no V2 re-stamp is required.

**No N+1.** Version and templateStyle ride the existing batched projection; the view tally is one
additional grouped query per page.

---

## 7. Verification performed

| Check | Command | Result |
|---|---|---|
| Frontend build | `npx vite build` | **exit 0** — no new warnings (pre-existing `INEFFECTIVE_DYNAMIC_IMPORT` and chunk-size warnings only) |
| Backend compile | `./mvnw -o compile -DskipTests` | **exit 0** |
| Backend tests | `./mvnw -o test -Dtest='!QuotationPdfRenderSmokeTest'` | **503 tests · 0 failures · 0 errors · 0 skipped** across 129 classes |

**The PDF render smoke test was excluded deliberately** — the owner runs it manually
(see memory `quotation-template-style-build`).

**One environmental trap hit and worth recording:** the first test run reported 398 errors —
`NoClassDefFound com/crm/travelcrm/workload/UserWorkload` and Mockito
*"Could not modify all classes"*. These were **not real failures**: `target/classes` was stale/torn.
Deleting `target/classes` and `target/test-classes` (not `mvnw clean`, which an open preview PDF can
break) and re-running gave a clean 503/0/0. This matches the existing memory
`mvn-test-pdf-preview-lock`.

---

## 8. Decisions taken, so they are not relitigated

| # | Decision | Reasoning |
|---|---|---|
| 1 | Colour only for `late` and `due` in Next action | A red row must always mean one thing. The screen already had six meaningless index colours; adding a seventh signal into that noise would have been pointless. |
| 2 | Stage counts as evidence of contact | An agent who calls and then moves the dropdown *has* contacted the customer. Requiring a log would make the column lie, and a lying column is worse than no column. |
| 3 | Weblink badge counts `EXTERNAL` views only | The tenant's own staff opening their own quotation is not customer interest. The analytics modal keeps its total/home/external split — that is a different question. |
| 4 | `QuotationRefDto.margin` carries **markup**, and the javadoc says so | Quotation has no vendor-cost column, so a true revenue-minus-cost margin cannot be computed. Markup is the only profit figure. The field is named after the existing column label; the javadoc prevents anyone reading it as a computed margin. |
| 5 | `margin` absent when `markup` is null, `0` when explicitly zero | "No markup entered" and "markup of zero" are different facts. Keeping them distinct lets the list show `—` instead of a page of `₹0.00`. |
| 6 | `viewCount` always set, `0` when nobody looked | "Nobody has opened it" is an answer the agent needs. It must not read as "we don't know". |
| 7 | Log follow-up mirrors onto the lead; a dateless log leaves it alone | A plain note must never silently cancel a follow-up somebody committed to. |
| 8 | `deleteLog` recomputes the mirror | Otherwise a deleted log leaves a permanent phantom overdue follow-up. |
| 9 | The demo widened the table by 72px and this was stated, not hidden | The demo proved the *content* idea; the width fix belongs to the full 8-column pass. |
| 10 | The Margin/Weblink columns were **not** deleted despite being dead | Deleting a column is a product decision. Fixing the data was the better answer, and it turned out to be possible. |

---

## 9. Revert record

**Reverted:** `travelcrmfe/travelcrmfrontend/src/features/leads/pages/AllLeads.jsx` — returned to
its pre-session state.

The revert was done **surgically, edit by edit — not with `git checkout`** — because that file
already carried unrelated uncommitted work from the lead-alert / bulk-import build
(`ImportLeadsModal` import and render, `importOpen` state, the Import button). A checkout would have
destroyed it. The same applies to `src/features/leads/api/leadService.js` (+21 uncommitted lines),
which this work never touched.

**Kept:** all five backend files in §5 and §6. Their diffs were verified to contain nothing but the
changes described here.

**Nothing was committed.** The owner commits manually in both repositories.

---

## 10. Open items

| # | Item | Where | Notes |
|---|---|---|---|
| 1 | Columns 16 → 8 + right drawer | FE | §3.1. Biggest single win. |
| 2 | Server-side filtering & paging on `GET /api/leads` | BE | §2.2. Until this lands, every count and filter on the page is a lie past 100 leads. |
| 3 | URL-synced filters | FE | §3.4 |
| 4 | Stage change via `PATCH` + optimistic + undo | FE | §2.4 — endpoint already exists |
| 5 | Stage filter covering all 8 stages, plus type/owner/views | FE | §2.3, §3.3 |
| 6 | Bulk actions on selection | FE | §2.6 — `leadAlertService.reassign` already exists |
| 7 | Keyboard shortcuts | FE | §2.7, §3.5 |
| 8 | Colour cleanup, tabular money, density, sortable headers, sticky header | FE | §2.8, §2.11, §3.6 |
| 9 | Mobile card layout | FE | §2.10, §3.7 |
| 10 | 100-lead cap notice | FE | §3.8 — stopgap until item 2 |
| 11 | `lastContactedAt` on the list DTO | BE | Would sharpen Next action rule 3 |
| 12 | Wire up the unused `GET /api/leads/board` Kanban | FE | §2.11 — backend already built |
| 13 | Re-apply the Next action column | FE | §4 is paste-ready |
