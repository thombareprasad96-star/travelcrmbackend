# Traveler Portal — TODO / Deferred

Consolidated build is phased. Phase 1 (Coming-Soon / Notify-me) is done. Everything below is
deferred — either a later phase, or a "Coming Soon" teaser (shown locked in the app, not built),
or blocked because it would need a **core CRM change** (forbidden by the zero-core-change rule).

## Audit gaps — features with NO backing data (shown as "Coming Soon", not built)
- **Pay Online (PAY_ONLINE)** — no payment gateway. Existing `PortalPaymentInitiation` is a stub
  returning `UNAVAILABLE`. → Coming Soon (Notify me). Real gateway (Razorpay/UPI) = future.
- **Payment schedule / installments + per-payment receipts** — there is **no Payment/installment
  entity**; payments are only fields on `Booking` (`paidAmount`, `totalPayable`, `paymentStatus`).
  A true paid/due schedule + per-payment receipt PDF needs either a new core table or core changes →
  NOT built. Portal shows the booking-level paid/pending only.
- **Live Cab Tracking (LIVE_TRACKING)** — no telemetry source. → Coming Soon.
- **Trip Memories (TRIP_MEMORIES)** — no photo store. → Coming Soon.
- **Refer & Earn (REFER_EARN)** — no referral/credits system. → Coming Soon.
- **Offline Itinerary (OFFLINE_MODE)** — PWA/offline not set up. → Coming Soon.
- **WhatsApp OTP delivery** — `WhatsAppSender` SPI exists but only as `LoggingWhatsAppSender`
  (stub). Real Meta Cloud API integration = future; OTP currently logs the code (dev).

## Later phases (buildable, additive — not yet built)
- **Phase 2**: Day-wise Itinerary (from `booking.sourceQuotationPublicId` → Quotation: hotels,
  vehicles, sightseeing — whitelist DTOs, zero cost/margin), Pax Details (`portal_pax_detail`,
  editable until N days before travel), Queries & Special Requests thread (`portal_query` +
  `portal_query_message`), Feedback (`portal_feedback`, one per booking), Travel Essentials
  (`portal_destination_essentials`, seed Nepal defaults), Weather widget (Open-Meteo, FE-only).
- **Phase 3 (during-trip)**: Today View (auto-detect today within travel dates), SOS strip,
  Trip Contacts (`portal_trip_contact`, visibleFrom gating), Report an Issue (HIGH-priority query).
- **Confirmation Tracker**: only Booking status is available today; component-wise chips
  (Hotel/Transport/Permits) have **no per-component status fields** → would need core columns.
  Show sensible defaults derived from `Booking.status`, or defer. Do NOT add columns to `Booking`.

## Staff-side (additive APIs exist / to add; NO staff UI this build)
- `GET /api/portal-admin/feature-interest/summary` — DONE (staff-chain secured, no UI yet).
- To add (additive, staff chain): manage `portal_trip_contact`, `portal_destination_essentials`,
  `portal_settings`, query replies, feedback dashboard, feature-interest widget.

## Explicitly out (future)
- Razorpay/UPI online payments · automated WhatsApp journey nudges (T-7 packing, T-1 driver,
  post-trip feedback) · photo memories gallery · referral & earn · PWA offline itinerary ·
  push notifications.