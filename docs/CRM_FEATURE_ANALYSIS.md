# Travel CRM — Feature Analysis & Gaps

> Codebase scan of `travelcrmbackend` (+ `travelcrmfrontend`) — inventory of what exists and what a Travel CRM should still have.
> Date: 2026-07-14

---

## Verdict

The platform is **far more complete than a typical CRM** — a mature, multi-tenant travel platform. Below: what's already built (strong), and the real gaps worth building next.

---

## What's already built (strong)

| Area | Built |
|---|---|
| **Sales pipeline** | Leads (8-stage: NEW → CONTACTED → FOLLOW_UP → QUALIFIED → PROPOSAL_SENT → CONVERTED → REOPENED → LOST), sources, types, itineraries, lead→booking conversion, follow-ups, reminders + scheduler |
| **Quotation engine** | 8-tab builder (hotel / flight / cruise / sightseeing / vehicle / addon), versioning, templates + match engine, public weblink + view analytics, PDF |
| **Bookings** | CRUD, payment ledger, service-items, vendor cost/revenue, invoice/voucher PDF, cancellation + refund + credit/debit notes, documents, Envers audit |
| **Masters** | Geography (country/destination/city), hotel/room/meal, sightseeing, vehicle, airline, cruise, addon, testimonial, tax rates |
| **Operations** | Vendor management (bank/financials/outstanding), Fleet/Vehicle Diary (trips, drivers, fuel, maintenance, doc-expiry alerts, dashboard) |
| **Customer-facing** | Traveler Portal (separate auth realm, OTP login, bookings, documents, itinerary, payment intent) |
| **Comms** | WhatsApp (Interakt), Email (config + logs), OTP, in-app + SSE notifications |
| **AI** | "Disha" assistant with tools + audit log |
| **Reports** | Dashboard, booking, activity, follow-up, geographic, intl/domestic, travel-date |
| **Platform (SuperAdmin)** | Tenant mgmt, subscriptions/billing (Razorpay), plans, usage/quotas, entitlements/feature-flags, analytics, audit, announcements, impersonation, ops/danger-zone, upgrade requests, platform config |
| **Foundation** | Multi-tenancy, granular per-user RBAC, B2B sub-agents w/ markup, onboarding checklist, trash/soft-delete |

---

## Gaps — what a Travel CRM should have but doesn't

### High-value, clearly missing

1. **Marketing & campaigns**
   Transactional WhatsApp/email exists, but no **bulk broadcast, drip sequences, customer segments, or auto birthday/anniversary wishes**. Biggest gap for a travel agency (re-marketing to past travelers drives repeat sales). `LoyaltyTier` enum exists but isn't leveraged.

2. **Customer-facing payment collection**
   Portal payment is a **stub SPI**; Razorpay is wired only for *platform* billing. Travelers can't actually pay online. Needed: "send payment link → collect → auto-reconcile to booking ledger". Highest-ROI addition.

3. **Task & team calendar**
   Reminders/follow-ups exist, but no unified **task board or calendar view** (assign tasks, due dates, team workload).

4. **Lead workflow automation**
   No **round-robin auto-assignment, SLA/escalation rules, or duplicate detection** (confirmed absent). Leads are manually assigned today.

5. **Accounting / GST depth**
   Have tax rates + invoice PDF + payment ledger, but no **GST-compliant invoice numbering series, e-invoice, TDS, Tally/Zoho/QuickBooks sync, or P&L**. Important for Indian travel agencies.

### Domain-specific travel gaps

6. **Supplier/inventory integrations**
   All masters are manual. No **GDS/flight (Amadeus/TBO), hotel API, or live fares**. Even one supplier API is a differentiator.

7. **Visa & passport tracking**
   Customer has passport fields, but no **visa application pipeline / passport-expiry alerting** as a workflow.

8. **Multi-currency**
   Currency appears as labels only; no **FX conversion** for international bookings/quotes.

9. **Post-trip feedback / NPS**
   Testimonials exist as a *master*, but no **automated post-trip survey → review capture** loop.

### Nice-to-have

10. **Staff sales incentive/commission** (sub-agent markup exists; staff commission doesn't)
11. **Referral/loyalty program** (tier enum unused)
12. **Custom report builder / sales forecasting**
13. **Telephony/CTI** — click-to-call + call logging
14. **Support ticket inbox** (noted deferred in platform build)
15. **Mobile app / PWA**

---

## Recommended sequencing

1. **#2 Customer payment collection** — fastest revenue impact
2. **#1 Marketing & campaigns** — repeat-sales driver
3. **#3 Tasks + #4 Lead automation** — team productivity
4. **#6 Supplier integrations + #7 Visa tracking** — travel-domain differentiators

---

*Next step: pick any gap above for a concrete design/build plan against the existing architecture.*