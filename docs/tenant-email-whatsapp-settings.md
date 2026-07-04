# Per-Tenant Email & WhatsApp Settings — Implementation Guide

How to implement **one Email (SMTP) config + one WhatsApp config per tenant**, wired into the
existing Settings pages. Written for **this** codebase's conventions (multi-tenancy, `ApiResponse`,
MapStruct/Lombok, Log4j2, `ddl-auto=update`). The existing **`company/`** module is the reference
pattern — a per-tenant singleton settings row — so copy its shape.

> Frontend already has the pages + service files:
> `EmailConfiguration.jsx` / `emailConfigurationService.js`,
> `WhatsAppConfiguration.jsx` / `whatsAppConfigService.js`, and the `CompanySettings.jsx` hub.
> The **backend for `/api/settings/email/**` and `/api/settings/whatsapp/**` does not exist yet** —
> that's what this doc builds.

---

## 0. Core idea — one settings row per tenant

Each tenant gets **exactly one** integration-settings row, looked up by `tenantId` (same as
`Company`). We store SMTP + WhatsApp fields on one entity `TenantSettings` (extends
`BaseTenantEntity`), created lazily on first save.

- Tenant is **never** a request param — always `TenantContext.getTenantId()` (populated by
  `JwtAuthFilter` from the JWT). See `CLAUDE.md` → "Getting current tenant ID".
- Use a **tenant-scoped finder** (`findByTenantId`), never bare `findById` (Hibernate
  `@Filter` only applies inside `@Transactional`; see `CLAUDE.md` → "never use bare findById").
- Because `spring.jpa.hibernate.ddl-auto=update`, the new table/columns are **auto-created** —
  no Flyway, no manual SQL. (Prod note in `CLAUDE.md`: migrate before switching to `validate`.)

### Secrets rule (non-negotiable)
- SMTP password and WhatsApp API key are **encrypted at rest** (AES-256), key from **env only**
  (`app.encryption.key`), never in code or committed config.
- **Never** return the secret to the frontend. Return a boolean `passwordSet` / `apiKeySet` so the
  UI shows `••••••••`. On save, only overwrite the secret when `passwordChanged` / `apiKeyChanged`
  is true.

---

## 1. New module layout

```
settings/
  entity/       TenantSettings, WaMessageLog
  repository/   TenantSettingsRepository, WaMessageLogRepository
  dto/          EmailConfigDTO, EmailConfigRequest, TestEmailRequest, TestEmailResponse
                WhatsAppConfigDTO, WhatsAppConfigRequest, TestWhatsAppRequest, TestWhatsAppResponse, WhatsAppStatsDTO
  service/      EmailConfigService, WhatsAppConfigService, TenantSettingsProvisioner
  controller/   EmailConfigController, WhatsAppConfigController
  crypto/       AesSecretCipher            (or reuse an existing crypto util if present)
  provider/     WhatsAppSender (SPI) + InteraktWhatsAppSender (or logging stub)
```

Add the package under `com.crm.travelcrm.settings`. Register it in `CLAUDE.md`'s module layout when done.

---

## 2. Entity — `TenantSettings` (one row / tenant)

```java
@Entity
@Table(name = "tenant_settings")
@Getter @Setter
public class TenantSettings extends BaseTenantEntity {   // gives id, publicId, tenantId, audit, soft-delete + @Filter

    // ---- Email / SMTP ----
    @Column(name = "smtp_host")            private String smtpHost;
    @Column(name = "smtp_port")            private Integer smtpPort;            // 587 / 465 / 25
    @Column(name = "encryption")           private String encryption;          // TLS | SSL | None
    @Column(name = "smtp_username")        private String smtpUsername;
    @Column(name = "smtp_password_enc")    private String smtpPasswordEnc;      // AES ciphertext (never plaintext)
    @Column(name = "email_from_address")   private String emailFromAddress;
    @Column(name = "email_from_name")      private String emailFromName;
    @Column(name = "email_last_tested_at") private LocalDateTime emailLastTestedAt;

    // ---- WhatsApp ----
    @Column(name = "whatsapp_api_key_enc") private String whatsAppApiKeyEnc;    // AES ciphertext
    @Column(name = "whatsapp_phone")       private String whatsAppPhone;
    @Column(name = "wa_template_name")     private String waTemplateName;
    @Column(name = "wa_template_language") private String waTemplateLanguage;   // default "en"
    @Column(name = "wa_header_image_url")  private String waHeaderImageUrl;
    @Column(name = "wa_last_tested_at")    private LocalDateTime waLastTestedAt;
}
```

```java
public interface TenantSettingsRepository extends JpaRepository<TenantSettings, Long> {
    Optional<TenantSettings> findByTenantId(Long tenantId);
}
```

### Lazy provisioning (like `TravelerAccount`)
```java
@Transactional
public TenantSettings currentOrCreate() {
    Long tenantId = TenantContext.getTenantId();
    if (tenantId == null) throw new IllegalStateException("TenantContext is empty");
    return repo.findByTenantId(tenantId).orElseGet(() -> {
        TenantSettings ts = new TenantSettings();
        // tenantId is auto-stamped by TenantEntityListener @PrePersist; no need to set manually
        return repo.save(ts);
    });
}
```

---

## 3. Secret encryption util

If a crypto helper already exists, reuse it. Otherwise:

```java
@Component
public class AesSecretCipher {
    private final SecretKeySpec key;
    public AesSecretCipher(@Value("${app.encryption.key}") String base64Key) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"); // 32 bytes = AES-256
    }
    public String encrypt(String plain) { /* AES/GCM, prepend IV, Base64 out */ }
    public String decrypt(String cipher) { /* reverse */ }
}
```

`application.properties`:
```properties
# 32-byte Base64 key — set via env in prod, NEVER commit a real value
app.encryption.key=${APP_ENCRYPTION_KEY:}
```

Prefer **AES/GCM** (authenticated). Store `iv:ciphertext` Base64. Rotate by re-encrypting on save.

---

## 4. Email — endpoints, service, DTOs

### Controller (`/api/settings/email`)
```java
@RestController
@RequestMapping("/api/settings/email")
@RequiredArgsConstructor
public class EmailConfigController {
    private final EmailConfigService service;

    @GetMapping("/config")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<EmailConfigDTO>> get() {
        return ResponseEntity.ok(ApiResponse.success("Email config", service.getConfig()));
    }

    @PostMapping("/config")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")     // same authority CompanyController uses for edits
    public ResponseEntity<ApiResponse<EmailConfigDTO>> save(@Valid @RequestBody EmailConfigRequest req) {
        return ResponseEntity.ok(ApiResponse.success("Email config saved", service.save(req)));
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<TestEmailResponse>> test(@Valid @RequestBody TestEmailRequest req) {
        return ResponseEntity.ok(ApiResponse.success("Test attempted", service.sendTest(req)));
    }
}
```

> **Envelope:** everything returns `ApiResponse<T>` (per `CLAUDE.md`). So the FE reads
> `res.data.data`, **not** `res.data`. Match the other real services (e.g. `companyService`).

### DTOs
- `EmailConfigDTO` (response): `configured`, `smtpHost`, `smtpPort` (formatted `"587 (TLS)"`),
  `portNumber`, `encryption`, `username`, **`passwordSet`** (boolean, never the value),
  `fromEmail`, `fromName`, `lastTestedAt`, `lastSavedAt`.
- `EmailConfigRequest`: `smtpHost` `@NotBlank`, `portNumber`, `encryption`, `username` `@NotBlank`,
  `passwordChanged` (boolean), `password` (only when changed), `fromEmail` `@Email`, `fromName`.
- `TestEmailRequest`: `recipientEmail` `@Email @NotBlank`.
- `TestEmailResponse`: `success`, `message`, `error` (nullable), `testedAt` (nullable).

### Service highlights
- **getConfig:** `currentOrCreate()`, map to DTO; `configured = smtpHost != null && !blank`;
  `passwordSet = smtpPasswordEnc != null && !blank`. Never decrypt here.
- **save:** update fields; only `smtpPasswordEnc = cipher.encrypt(req.password)` when
  `req.passwordChanged && password not blank`. `@Transactional`.
- **sendTest:** build a **per-tenant** `JavaMailSenderImpl` from stored settings (host, port,
  username, `cipher.decrypt(smtpPasswordEnc)`, TLS/SSL props), send, set `emailLastTestedAt`.
  Catch `MailException` and return a **structured error** (`success=false`, message) — do **not**
  throw 500. If not configured → `BusinessException(..., HttpStatus.UNPROCESSABLE_ENTITY)`.

```java
JavaMailSenderImpl s = new JavaMailSenderImpl();
s.setHost(ts.getSmtpHost());
s.setPort(ts.getSmtpPort() != null ? ts.getSmtpPort() : 587);
s.setUsername(ts.getSmtpUsername());
s.setPassword(cipher.decrypt(ts.getSmtpPasswordEnc()));
Properties p = s.getJavaMailProperties();
p.put("mail.smtp.auth", "true");
if ("TLS".equals(ts.getEncryption())) p.put("mail.smtp.starttls.enable", "true");
else if ("SSL".equals(ts.getEncryption())) p.put("mail.smtp.ssl.enable", "true");
```

> Build the sender **dynamically per tenant** — do **not** use a global `spring.mail.*` bean,
> because each tenant has its own SMTP creds.

---

## 5. WhatsApp — endpoints, provider SPI, stats

### Controller (`/api/settings/whatsapp`)
`GET /config`, `POST /config`, `POST /test`, `GET /stats` — same `@PreAuthorize` split
(reads `isAuthenticated()`, writes `SETTINGS_MANAGE`), same `ApiResponse` envelope.

### DTOs
- `WhatsAppConfigDTO`: `configured`, **`apiKeySet`**, `templateName`, `templateLanguage`,
  `headerImageUrl`, `whatsAppPhone`, `lastTestedAt`, `lastSavedAt`.
- `WhatsAppConfigRequest`: `apiKeyChanged`, `apiKey` (only when changed), `templateName` `@NotBlank`,
  `templateLanguage` `@NotBlank`, `headerImageUrl` (optional).
- `TestWhatsAppRequest`: `phoneNumber` `@Pattern(regexp="\\d{10}")`.
- `TestWhatsAppResponse`: `success`, `message`, `error`, `testedAt`.
- `WhatsAppStatsDTO`: `messagesSent`, `deliveryRate` (`"98%"`), `lastTestedAt`, `apiStatus`
  (`Active`/`Inactive`), `apiStatusSub`.

### Provider as an SPI (swappable — follow the codebase's `PortalPaymentInitiation` / OTP-sender pattern)
```java
public interface WhatsAppSender {                       // SPI
    void send(TenantSettings ts, String toPhoneE164, List<String> bodyValues);
}

@Component
@ConditionalOnMissingBean(name = "realWhatsAppSender")  // logging stub is the fallback
class LoggingWhatsAppSender implements WhatsAppSender { /* log only */ }

// Real impl (Interakt / Meta Cloud API) drops in later with no other change:
@Component("realWhatsAppSender") @Primary
class InteraktWhatsAppSender implements WhatsAppSender { /* RestTemplate/WebClient call, Authorization: Basic <apiKey> */ }
```

### Stats + logging
Add `WaMessageLog extends BaseTenantEntity` (`phone`, `template`, `status` SENT/FAILED, `errorMsg`,
`sentAt`). On every send/test, save a row. `getStats()`:
```java
long sent  = waLogRepo.countByTenantIdAndStatusAndSentAtAfter(tenantId, "SENT", startOfMonth);
long total = waLogRepo.countByTenantIdAndSentAtAfter(tenantId, startOfMonth);
String rate = total > 0 ? Math.round(sent * 100.0 / total) + "%" : "—";
```
`apiStatus = apiKeySet ? "Active" : "Inactive"`.

---

## 6. Tenant isolation checklist (must-do for both modules)

- [ ] Entities extend **`BaseTenantEntity`** → `tenantId` auto-stamped, `@Filter` carried.
- [ ] All service methods `@Transactional` (so the Hibernate tenant `@Filter` is actually active).
- [ ] Lookups via **`findByTenantId(TenantContext.getTenantId())`** only. No bare `findById`.
- [ ] No `tenantId` accepted from the request/body.
- [ ] Secrets AES-encrypted; responses expose only `passwordSet` / `apiKeySet` booleans.
- [ ] Writes gated by `@PreAuthorize("hasAuthority('SETTINGS_MANAGE')")`; reads `isAuthenticated()`.
- [ ] Return `ApiResponse<T>` everywhere.

---

## 7. Frontend fixes (needed even after the backend exists)

Two current FE bugs will cause **401 / wrong parsing** regardless of backend:

1. **Wrong token key + private axios.** `emailConfigurationService.js` and
   `whatsAppConfigService.js` build their own axios and read
   `localStorage.getItem("authToken")`. The app stores the JWT under **`"token"`**
   (`CLAUDE.md` → Auth & token storage). Fix: **import the shared `./axiosInstance`** (it already
   attaches `Bearer <token>` and handles 401) and delete the local axios + interceptors.

2. **Envelope unwrap.** Backend returns `ApiResponse<T>`, so real data is **`res.data.data`**.
   Update the pages/services to read `.data.data` (the `companyService` consumers already do this).

### Hub wiring (`CompanySettings.jsx`)
Replace the hardcoded `STATS` / card badges / `quickStats` with state; on mount use
`Promise.allSettled` so one missing endpoint never blanks the page:

```jsx
useEffect(() => {
  Promise.allSettled([
    companyService.getSubscription(),      // General card → plan
    emailConfigurationService.getConfig(), // Email card → configured?
    whatsAppConfigService.getConfig(),     // WhatsApp card → apiKeySet?
    whatsAppConfigService.getStats(),      // WhatsApp quick stats
  ]).then(([sub, email, wa, waStats]) => {
    const val = r => (r.status === "fulfilled" ? r.value.data.data : null); // .data.data
    // map into state → badge label ("Configured"/"Not Configured"), quickStats, top pills
    // where no data → show "—" / "Not set up"
  });
}, []);
```

---

## 8. Config / properties to add

```properties
# AES key for encrypting tenant SMTP passwords + WhatsApp API keys (env only in prod)
app.encryption.key=${APP_ENCRYPTION_KEY:}

# JavaMail timeouts (the per-tenant sender is built in code; these are just safety limits)
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=5000
spring.mail.properties.mail.smtp.writetimeout=5000
```

`pom.xml`: add `spring-boot-starter-mail` for `JavaMailSender` (WhatsApp uses the existing web/RestTemplate stack).

---

## 9. Optional — wire into existing stubs

The codebase already has **logging stubs** you can now back with real per-tenant settings:
- `otp/` → `OtpDeliverySender` (Email/WhatsApp stubs) routed by `OtpSenderResolver`.
- `notification/` → `EmailNotificationChannel` (stub).

Once `TenantSettings` exists, those stubs can read the tenant's SMTP/WhatsApp config and send for
real — so OTP emails, notification emails, and quotation delivery all use the tenant's own sender.
Keep them behind the SPI so swapping a provider stays a one-bean change.

---

## 10. Build order (suggested)

1. `TenantSettings` entity + repository + `AesSecretCipher` + `app.encryption.key`.
2. Email: DTOs → service → controller → test-send. Fix `emailConfigurationService.js` (shared axios, `token`, envelope). Verify `EmailConfiguration.jsx` end-to-end.
3. WhatsApp: `WaMessageLog` + DTOs → `WhatsAppSender` SPI (stub) → service (config/stats) → controller. Fix `whatsAppConfigService.js`. Add the real provider bean when API keys are ready.
4. `CompanySettings.jsx` hub → dynamic status via `Promise.allSettled`.
5. Update `CLAUDE.md` module layout with the new `settings/` module.