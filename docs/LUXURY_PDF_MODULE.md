# LUXURY quotation PDF — headless Chromium renderer

The fourth quotation design. Unlike CLASSIC, MODERN and PREMIUM it is **not** rendered by
OpenPDF/Flying Saucer — it goes Thymeleaf → an internal URL → headless Chromium → PDF.

> **Status.** Backend, template, tests and the frontend picker are done and verified end to end on a
> local machine. **Docker is NOT done** — a deployed image has no Chromium, so LUXURY answers 503
> there while the other three designs keep working. See [Deployment](#deployment-not-done-yet).

---

## Why a second engine

The design is built on CSS Grid, flexbox, `object-fit` and gradient overlays. Flying Saucer
implements roughly CSS 2.1: it does not *fail* on those rules, it **ignores** them. Routing this
template through the existing engine would therefore produce a plausible-looking PDF that is silently
wrong — an unstyled stack of blocks. Chromium is the only engine in reach that lays it out as drawn.

Everything else is shared: the same `QuotationResponseDto`, the same server-computed pricing, the
same tenant/sub-agent branding resolution.

---

## Routing

```
GET /api/quotations/{publicId}/pdf?style=…
        │
        └─► QuotationServiceImpl ─► QuotationPdfRouter
                                        │
             CLASSIC / MODERN / PREMIUM ─┴─► LegacyQuotationPdfRenderer
                                        │        └─► QuotationPdfService   (UNCHANGED)
                                        │
                                 LUXURY ─┴─► ChromiumLuxuryPdfRenderer
                                                 ├─ LuxuryQuotationPdfMapper  → display-ready DTO
                                                 ├─ LuxuryPreviewTokenService → one-shot token
                                                 ├─ PlaywrightBrowserManager  → shared Chromium
                                                 └─ GET /internal/pdf/quotations/{token}/luxury-preview
```

`QuotationPdfRouter` picks by `QuotationPdfRenderer.supports(style)`. A style no renderer claims is a
**501**, never a quiet substitution — a document produced in a design nobody chose is worse than an
error.

All four PDF call sites in `QuotationServiceImpl` go through the router (`generatePdf`, `getPdf`,
`getPublicPdf`, the e-mail path), so an emailed quotation is the same document as a downloaded one.

---

## Why Chromium fetches a URL instead of being handed HTML

`page.setContent(html)` gives the document a blank origin, so every relative reference in it — the
fallback artwork, the fonts — resolves against nothing and silently fails to load. The page still
renders; it just renders wrong. Navigating to a real URL on this application makes `static/` resolve
exactly as it does in a browser.

That URL has to be reachable without a JWT (Chromium has no credential), which would normally mean an
unauthenticated endpoint taking a quotation UUID — i.e. a way to read any tenant's quotation by
guessing an id. The token closes it by **carrying the rendered document itself**:

1. The authenticated, tenant-scoped service call loads the quotation and maps it.
2. `LuxuryPreviewTokenService.mint()` stashes the finished view model behind 32 bytes of
   `SecureRandom` and returns the token.
3. Chromium navigates to `/internal/pdf/quotations/{token}/luxury-preview`.
4. The controller redeems the token — **single use**, 60-second TTL — and renders what it was handed.
   It performs no lookup, so there is no id to tamper with; a forged token resolves to nothing.

**Order is load-bearing:** the browser is obtained *before* the token is minted. See
[Bugs found](#bugs-found-and-fixed).

---

## Files

### New — backend (14)

| File | Role |
|---|---|
| `quotation/pdf/QuotationPdfRenderer.java` | Interface: `supports(style)` + `render(dto)` |
| `quotation/pdf/LegacyQuotationPdfRenderer.java` | CLASSIC/MODERN/PREMIUM — pure delegation to the unmodified `QuotationPdfService` |
| `quotation/pdf/ChromiumLuxuryPdfRenderer.java` | LUXURY: concurrency gate, context lifecycle, PDF options, output validation |
| `quotation/pdf/QuotationPdfRouter.java` | Style → renderer |
| `quotation/pdf/LuxuryPreviewTokenService.java` | One-shot, short-TTL tokens holding the view model |
| `quotation/pdf/InternalLuxuryPdfPreviewController.java` | Serves the HTML Chromium loads |
| `quotation/pdf/LuxuryPdfUnavailableException.java` | 503 — feature off / Chromium absent / too busy |
| `quotation/pdf/LuxuryPdfRenderException.java` | 500 — navigation, readiness or empty-output failure |
| `quotation/pdf/config/LuxuryPdfProperties.java` | `@ConfigurationProperties("pdf.luxury")` |
| `quotation/pdf/playwright/PlaywrightBrowserManager.java` | Lazy, shared, synchronized browser + shutdown |
| `quotation/pdf/dto/LuxuryQuotationPdfDto.java` | Display-ready model (`${pdf}` in the template) |
| `quotation/pdf/mapper/LuxuryQuotationPdfMapper.java` | Entity/DTO → display model |
| `quotation/pdf/mapper/LuxuryDisplayFormat.java` | Money, dates, counts, rich-text → bullets |
| `quotation/pdf/mapper/LuxuryPdfPaginator.java` | Page chunking + policy-block packing |

### New — resources

- `templates/pdf/quotation-luxury.html`
- `static/images/pdf/fallbacks/` — 6 neutral placeholders (`luxury-cover.jpg`, `company-logo.png`,
  `hotel-placeholder.jpg`, `vehicle-placeholder.jpg`, `sightseeing-placeholder.jpg`,
  `agent-placeholder.png`)

### New — tests (3 files, 55 tests)

- `quotation/pdf/mapper/LuxuryDisplayFormatTest.java`
- `quotation/pdf/mapper/LuxuryPdfPaginatorTest.java`
- `quotation/pdf/mapper/LuxuryQuotationPdfMapperTest.java`

### Modified — backend (8)

| File | Change |
|---|---|
| `quotation/enums/TemplateStyle.java` | `+ LUXURY`, `+ isLegacyEngine()` |
| `quotation/service/QuotationServiceImpl.java` | 4 render call sites → `QuotationPdfRouter` |
| `quotation/controller/QuotationController.java` | Human filename + sanitizer |
| `quotation/service/QuotationPdfService.java` | **Only** a `case LUXURY -> throw` in the exhaustive switch. No behaviour change |
| `auth/security/SecurityConfig.java` | `permitAll` for the internal preview route and the fallback images |
| `pom.xml` | `com.microsoft.playwright:playwright:1.47.0` |
| `application.properties` | `pdf.luxury.*` block |
| `db/indexes.sql` | `quotations_template_style_check` now names `LUXURY` |

### Modified — frontend (2)

`C:\Users\hp\Desktop\travelcrm\frontend1`

| File | Change |
|---|---|
| `features/quotation/components/QuotationStyleModal.jsx` | 4th entry in `STYLES` (`Gem` icon, gold accent) |
| `features/quotation/api/quotationService.js` | Comment only — no code change |

Nothing else was needed: `shared/hooks/usePdfDownload.js` and `shared/ui/PdfDownloadLoader.jsx`
already provide the loading state, duplicate-click guard and error toast, and the shared interceptor
already toasts a 5xx.

### NOT touched

`Dockerfile`, jar packaging, `db/migration/V2__lead_code.sql`, `QuotationPdfService` behaviour, any
pricing code.

---

## DTO mapping

`LuxuryQuotationPdfMapper` **formats; it never calculates.** Every rupee figure comes straight from
`QuotationResponseDto.Totals`, which `QuotationMapper.computeTotals` produced on the read path.

| PDF field | Source |
|---|---|
| `quotationCode` | Composed `QT-{yy}-{quoteNo}` — see [Not available](#fields-that-were-not-available) |
| `customerName`, `destination` | `customer.name`, `customer.destination` |
| `packageTitle` | `quotation.title` |
| `duration` | `nights` + `days` → `"6 Nights / 7 Days"` |
| `travelDateRange` | `customer.travelDate` + `nights` → `"20 Aug 2026 – 26 Aug 2026"` |
| `travellerSummary` | `adults`/`children`/`infants` → `"2 Adults, 1 Child"` |
| `coverImageUrl` (also snapshot/inclusion/closing) | `quotation.coverImageUrl` |
| `company.*` | `CompanyRepository.findByTenantId`, then `SubAgentBrandingService.resolve(ownerUserId)` overrides identity fields only |
| `agent.*` | `UserRepository.findByIdAndTenantIdAndDeletedAtIsNull(ownerUserId, tenantId)` |
| `itineraryPages[].days[]` | `sightseeing.days[].activities[]`, flattened one card per day |
| `hotelPages[].hotels[]` | `hotel.hotels[]`; nights derived from check-in/out |
| `hotelPages[0].noteLines` | `hotel.notes` → bullets |
| `transport` | `vehicle.vehicles[]`; notes de-duplicated across rows |
| `sightseeingItems`, `sightseeingImages` | `sightseeing.days[].activities[]` (gallery capped at 3) |
| `inclusions`, `exclusions` | `quotation.inclusions/exclusions` |
| `pricing.rows[]` | Per-section `amount`, shown only when **included AND non-zero** — the same rule `QuotationPdfService.rendersSomething` applies to the other designs |
| `pricing.subtotal/discountAmount/taxAmount/grandTotal/perAdult` | `Totals` verbatim, formatted |
| `pricing.statusLabel` / `statusCode` | `quotationStage` → `"Sent"` / `"SENT"` |
| `policyPages` | `paymentPolicies` + `cancellationPolicies` + `bookingTerms`, packed |
| `fallback*ImageUrl` | Constant paths under `/images/pdf/fallbacks/` |

### Fields that were not available

Nothing was invented for these. Each is `null` and the template hides the block.

| Field | Why |
|---|---|
| `quotationCode` | No stored code column — the quotation carries a bare `quoteNo`, unlike Lead (`LD-26-0001`) or Booking. **Composed at render time** as `QT-{yy}-{quoteNo}`; nothing was added to the schema. Falls back to the version label, never to a UUID |
| `tripType` | Domestic/International is not stored anywhere on the quotation |
| `closingMessage` | No closing-copy field. The template's own static sign-off is used |
| `qrCodeImageUrl`, `qrCaption` | The share link is minted by a separate endpoint and is not part of the render model |
| `agent.photoUrl`, `agent.signatureUrl` | No such column on `User`; the placeholder is used |
| `paymentSchedule[].dueLabel` / `.amount` | Stored as free text, not as structured amount/date pairs. Parsing "50% on booking" into a figure would be the mapper inventing a payment obligation |
| `cancellationRows[].charge` | Same — free text in, one row out |
| Travel **end** date | Not stored. Derived from `travelDate + nights` (arithmetic on known values, not a guess) |

---

## Display rules worth knowing

**Money is formatted in Java, never in the template.** Chromium renders under whatever locale the
container has; `₹1,25,000` and `₹125,000` are the same number and different documents.

Indian grouping is **hand-written** (`LuxuryDisplayFormat.groupIndian`). Two obvious approaches were
tried and both are wrong:

- `NumberFormat.getNumberInstance(en-IN)` groups in plain thousands → `₹100,000`
- a `"#,##,##0"` DecimalFormat pattern — `DecimalFormat` carries a single `groupingSize` and takes the
  last interval in the pattern, so it also grouped by threes → `₹1,050,000`

Both produce a plausible number, which is why they survived until a test asserted the exact string.

**Zero is a value.** Discount, GST and TCS print at `₹0` rather than disappearing. A customer who
cannot see that nothing was added cannot reconcile the total against the lines above it. `perAdult` is
the exception: it is hidden when unknown, because `₹0` there reads as a free trip.

**No raw enums, UUIDs or ISO timestamps** reach the page. `statusCode` (machine) and `statusLabel`
(human) are separate fields.

**Rich text becomes bullets, not a paragraph.** `LuxuryDisplayFormat.bullets()` splits on
`</li>`, `</p>`, `</div>`, `</h1-6>` and `<br>`. When the markup yields a single line it falls back to
splitting on sentences — a stop/question/exclamation followed by whitespace **and a capital letter**,
which keeps `2.5 hours` and `₹1,25,000.50` intact, plus an abbreviation guard so `Mr. Sharma` is not
cut in half. When the agent structured the text themselves, that structure wins and is not re-split.
Author text is never edited — trailing punctuation stays as typed.

---

## Pagination

The template is a deck of fixed-height A4 sheets, so each page can carry its own footer and page
number. The cost is that **overflow is clipped silently** — the PDF looks finished and the content is
gone. All splitting therefore happens in Java (`LuxuryPdfPaginator`), never in CSS.

| Content | Rule |
|---|---|
| Itinerary days | 4 per page; **3** when any description exceeds ~320 chars. Decided once for the whole itinerary so the grid does not change density halfway through. A day is never split |
| Hotels | 3 per page |
| Sightseeing gallery | 3 images |
| Payment schedule + cancellation + terms | **Packed** — greedy first-fit into a 32-line budget per sheet, in reading order. Short blocks share a sheet; a long terms list spills to as many as it needs, with "(continued)" on later parts |

The packing replaced one-section-per-sheet, which printed two nearly-empty A4 pages on a typical
quotation. It also fixed a latent bug: the payment schedule used to sit under the price table on the
pricing page and would overflow that fixed-height sheet without a trace.

---

## Image failure handling

Every image has a `th:src` for the real URL **and** an `onerror` swap to a local fallback. Both are
needed — `th:src` covers "no URL stored", `onerror` covers "URL stored but 404s at render time",
which is the common case with expired or deleted Cloudinary assets.

The template's readiness script resolves its latch on image `error` as well as `load`, so one dead URL
costs a placeholder rather than hanging the render until timeout. A 15-second backstop covers a URL
that neither loads nor errors.

Placeholders are deliberately obvious (plain shapes + a caption, no branding, no stock photography): a
placeholder that looks like real artwork is one nobody notices shipped.

---

## Resource management

- **One Chromium process**, launched lazily on the first LUXURY render behind a double-checked lock.
  Never at boot — a server without the browser starts normally and serves the other three designs.
- **A fresh `BrowserContext` per PDF**, closed in a `finally`. A leaked context keeps its page's
  bitmaps alive for the life of the process.
- **`Semaphore(maxConcurrentJobs, fair)`** — a memory ceiling, not a throughput knob. Each job is a
  full A4 document with photographs in memory; unbounded, a burst of downloads OOM-kills the CRM.
  Over the queue timeout the caller gets a 503 telling them to retry.
- **`@PreDestroy`** closes the browser, so a redeploy does not leave orphan Chromium processes.
- Launch failures are **not cached** — the next request retries, so installing the browser does not
  require an application restart.

The output is validated before it is returned: ≥1 KB and a `%PDF` magic number. Chromium can return
bytes after a partial failure, and an empty PDF is worse than an error because the agent sends it
before discovering it does not open.

---

## Configuration

```properties
pdf.luxury.enabled=${PDF_LUXURY_ENABLED:true}
pdf.luxury.browser-headless=${PDF_LUXURY_HEADLESS:true}
pdf.luxury.navigation-timeout-ms=${PDF_LUXURY_NAV_TIMEOUT_MS:30000}
pdf.luxury.render-timeout-ms=${PDF_LUXURY_RENDER_TIMEOUT_MS:60000}
pdf.luxury.max-concurrent-jobs=${PDF_LUXURY_MAX_CONCURRENT_JOBS:2}
pdf.luxury.queue-timeout-ms=${PDF_LUXURY_QUEUE_TIMEOUT_MS:20000}
pdf.luxury.internal-base-url=${PDF_LUXURY_INTERNAL_BASE_URL:http://127.0.0.1:${server.port}}
pdf.luxury.preview-token-ttl-seconds=${PDF_LUXURY_PREVIEW_TOKEN_TTL_SECONDS:60}
```

`internal-base-url` must stay **loopback**. The renderer is a browser on this same host fetching our
own template; pointing it at the public hostname would send every render out through nginx and back.

`enabled=false` disables LUXURY alone — the other three are untouched by every key here.

---

## Database

`SchemaEnumConstraintValidator` guards `quotations.template_style`, so adding the enum constant
without widening the CHECK constraint **refuses to boot** — by design.

`db/indexes.sql:495` now reads:

```sql
CHECK (template_style IN ('CLASSIC','MODERN','PREMIUM','LUXURY'));
```

**That file does not run on this machine** — `application-local.properties` sets
`SQL_INIT_MODE=never`. The constraint was therefore applied directly, inside a transaction:

```sql
BEGIN;
ALTER TABLE quotations DROP CONSTRAINT IF EXISTS quotations_template_style_check;
ALTER TABLE quotations ADD CONSTRAINT quotations_template_style_check
    CHECK (template_style IN ('CLASSIC','MODERN','PREMIUM','LUXURY'));
COMMIT;
```

No data was touched — the constraint only widened, so no existing row could violate it.

**Any other environment needs the same statement** (or `SQL_INIT_MODE=always` so `indexes.sql` runs).
`V2__lead_code.sql` has no `template_style` block and was deliberately left alone.

---

## Local setup

Chromium is a separate ~170 MB download; the JAR does not contain it. Once per machine:

```bash
mvnw.cmd org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

Installs to `%USERPROFILE%\AppData\Local\ms-playwright`. Do **not** pass `--with-deps` on Windows —
that flag is Linux-only.

Then run as usual (`mvnw spring-boot:run` or IntelliJ). No jar and no Docker needed.

Without the browser the app still boots and CLASSIC/MODERN/PREMIUM still work; only LUXURY answers
503, because the launch is lazy.

```bash
mvnw.cmd test -Dtest=LuxuryDisplayFormatTest,LuxuryPdfPaginatorTest,LuxuryQuotationPdfMapperTest
```

---

## Verification performed

Live, against a real quotation on `localhost:8080`:

| Template | Result |
|---|---|
| CLASSIC | 200 · 7.3 MB · 4.3 s |
| MODERN | 200 · 156 KB · 1.2 s |
| PREMIUM | 200 · 199 KB · 1.1 s |
| **LUXURY** | 200 · 1.66 MB · **9 pages** · 1.1 s warm, ~20 s on the first render (Chromium launch) |

Output checked for the `%PDF` header, a page count, and a clean `%%EOF`.

**55 unit tests pass.** They cover Indian currency grouping, genuine zeros, date shapes and ranges,
traveller pluralisation, star labels, enum→label, rich text → bullets, sentence splitting with decimal
and abbreviation guards, itinerary/hotel/terms chunking, policy packing, and the mapper's behaviour on
a near-empty quotation.

---

## Bugs found and fixed

**1. Token minted before the browser was launched.** The first render of a process pays for the cold
Chromium launch — measured at 84 s on a Windows machine with a freshly downloaded bundle. The
60-second token expired before the browser ever navigated, the preview answered 404, and the render
then sat until its own timeout before failing with a 500. Fixed by obtaining the browser first, so the
TTL only has to cover the navigation itself.

**2. Indian digit grouping did not work.** `₹100,000` instead of `₹1,00,000`, then `₹1,050,000`
instead of `₹10,50,000` after the first attempted fix. Caught by a test asserting the exact string;
see [Display rules](#display-rules-worth-knowing).

**3. Doubled bullet markers.** `plain()` maps `<li>` to a leading `•`, and the template draws its own
marker, so lines printed as `• • Visit the fort`. The strip regex removed only one.

---

## Deployment — NOT done yet

A built image has **no Chromium**, so LUXURY returns 503 in production today. CLASSIC, MODERN and
PREMIUM are unaffected.

To enable it, the runtime stage of the `Dockerfile` needs:

1. `ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright` — the default `~/.cache/ms-playwright` installs
   under root's home during build and is invisible to the `travelcrm` user the container runs as.
2. `fonts-dejavu-core` (carries U+20B9 — without it every price prints as ▯) and `fonts-liberation`.
   The `eclipse-temurin:21-jre-jammy` base image ships no fonts.
3. A build-time `playwright install --with-deps chromium`. `--with-deps` pulls the ~40 shared
   libraries headless Chromium needs; without them the bundle exists on disk and cannot execute.
   Build time, never startup — installing on boot re-downloads on every container start.
4. Chromium launch flags are already handled in code (`PlaywrightBrowserManager.CONTAINER_SAFE_ARGS`):
   `--disable-dev-shm-usage` (Docker's default 64 MB `/dev/shm` is exhausted by an A4 page full of
   photographs, killing the tab mid-render) and `--no-sandbox` (the image runs non-root; acceptable
   because the browser only ever navigates to one loopback URL serving our own template).

Expect roughly **+300 MB** of image size: ~170 MB browser, ~120 MB system libraries, ~10 MB fonts.

A draft of these Dockerfile changes was written and then reverted on request; it was never built or
verified, so it is not recorded here as working.

---

## Known limitations

| | |
|---|---|
| **The template is not the supplied design** | `quotation-luxury.html` was written against the documented DTO contract because the intended HTML was never provided. The Java side binds to `${pdf}` with the agreed field names, so the real file should drop in without Java changes |
| **No agent card on the public share link** | `getPublicPdf` runs with no `TenantContext`, and resolving the owning user without a tenant predicate would be a cross-tenant read. Company branding still resolves; the individual's card is hidden |
| **Company branding on the public path** | Same cause — falls back to the configured `quotation.pdf.*` defaults, exactly as the other three designs do there |
| **The public share link can trigger a Chromium render** | Anyone holding a capability URL for a LUXURY-styled quotation can cause one. The concurrency gate caps the damage (excess requests get a 503), but it is unauthenticated work. Consider restricting LUXURY to the authenticated download path |
| **Tokens are in-memory** | Single-node only. Correct today — the token's whole life is the few hundred milliseconds between minting and Chromium fetching it on the same host — but a multi-node deployment behind a load balancer would need the browser and the app to stay on the same instance (they do, via loopback) |
| **Line budgets are estimates** | `PAGE_BUDGET_UNITS` and `CHARS_PER_LINE` are derived from the template's own measurements, not measured at render time. Deliberately conservative: under-filling costs whitespace, over-filling costs clipped text |

---

## Related

- `docs/FLEET_MODULE.md` — module documentation style this follows
- `QuotationPdfService` — the unchanged CLASSIC/MODERN/PREMIUM engine
- `SchemaEnumConstraintValidator` — why an enum constant is a two-place change
