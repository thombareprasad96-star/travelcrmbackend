package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.QuotationSection;
import com.crm.travelcrm.subagent.service.SubAgentBrandingService;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.pdf.ITextRenderer;
import org.openpdf.text.pdf.BaseFont;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a quotation to a PDF byte array: Thymeleaf produces well-formed XHTML from
 * {@code templates/pdf/quotation.html}, then OpenPDF's {@link ITextRenderer}
 * (the Flying Saucer fork) lays it out as A4.
 *
 * <p>The template is parsed in {@link TemplateMode#XML} so the output is guaranteed
 * well-formed for the renderer (HTML mode would emit unclosed void tags such as
 * {@code <br>} that the XML-based renderer rejects). Company branding is taken fresh
 * from the current tenant's {@link Company} profile (resolved by {@link TenantContext}),
 * falling back to the configured {@code quotation.pdf.*} properties when a field is
 * blank or when no tenant is in scope (e.g. the public share-link path).
 */
@Service
@Slf4j
public class QuotationPdfService {

    /**
     * Private CSS font-family used ONLY by the star-rating span in the template
     * ({@code .hcard-namestar { font-family: 'StarFont' }}). The bundled DejaVu Sans is
     * registered under this name so it never shadows the document's Helvetica body font —
     * we only borrow DejaVu for the one glyph (★, U+2605) the base-14 fonts lack.
     */
    private static final String STAR_FONT_FAMILY = "StarFont";

    /** Classpath location of the bundled star-capable font (shipped inside the JAR). */
    private static final String STAR_FONT_RESOURCE = "fonts/DejaVuSans.ttf";

    private final TemplateEngine templateEngine;
    private final CompanyRepository companyRepository;
    private final SubAgentBrandingService brandingService;

    /**
     * The bundled font extracted to a temp file once at startup. OpenPDF's font resolver reads
     * the TTF from a filesystem path, so we materialise the classpath resource here (works the
     * same in an exploded target/ dir and inside a deployed JAR). Null if extraction failed —
     * rendering then proceeds without the star glyph rather than failing the whole PDF.
     */
    private final File starFontFile = extractStarFont();

    private final String companyName;
    private final String companyTagline;
    private final String companyPhone;
    private final String companyEmail;
    private final String companyWebsite;
    private final String companyAddress;
    private final String companyLogoUrl;
    private final String brandColor;

    public QuotationPdfService(
            CompanyRepository companyRepository,
            SubAgentBrandingService brandingService,
            @Value("${quotation.pdf.company-name:TravelCRM}") String companyName,
            @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") String companyTagline,
            @Value("${quotation.pdf.company-phone:}") String companyPhone,
            @Value("${quotation.pdf.company-email:}") String companyEmail,
            @Value("${quotation.pdf.company-website:}") String companyWebsite,
            @Value("${quotation.pdf.company-address:}") String companyAddress,
            @Value("${quotation.pdf.company-logo-url:}") String companyLogoUrl,
            @Value("${quotation.pdf.brand-color:#2563EB}") String brandColor) {

        this.companyRepository = companyRepository;
        this.brandingService = brandingService;
        this.companyName = companyName;
        this.companyTagline = companyTagline;
        this.companyPhone = companyPhone;
        this.companyEmail = companyEmail;
        this.companyWebsite = companyWebsite;
        this.companyAddress = companyAddress;
        this.companyLogoUrl = companyLogoUrl;
        this.brandColor = brandColor;

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);   // emit well-formed XHTML for the renderer
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    public byte[] render(QuotationResponseDto dto) {
        long startNanos = System.nanoTime();
        log.debug("render() start | quotation={} | title='{}'", dto.getPublicId(), dto.getTitle());

        Context ctx = new Context();
        ctx.setVariable("q", dto);
        ctx.setVariable("customer", dto.getCustomer());
        ctx.setVariable("totals", dto.getTotals());
        ctx.setVariable("fmt", new PdfFormat());
        ctx.setVariable("generatedOn", LocalDate.now());
        // Classic's bundled artwork, as real file URIs (see the field javadoc — this is the fix for
        // the file:///C:/ dev-machine paths that left every production PDF without its art).
        ctx.setVariable("travelBgUrl", toUriOrNull(travelBgFile));
        ctx.setVariable("bannerUrl", toUriOrNull(bannerFile));

        // Branding is taken fresh from the tenant's editable Company profile (companies table),
        // resolved by the current tenant id — never from the request/DTO. Each field falls back to
        // the configured quotation.pdf.* default when blank. On the public share-link path
        // TenantContext is null (no auth/tenant), so the configured defaults are used there.
        String cName    = companyName;
        String cLogo    = companyLogoUrl;
        String cPhone   = companyPhone;
        String cEmail   = companyEmail;
        String cWebsite = companyWebsite;
        String cAddress = companyAddress;
        String cColor   = brandColor;
        String cGst     = null;
        Integer cReviews = null;
        Integer cYears   = null;

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Company co = companyRepository.findByTenantId(tenantId).orElse(null);
            if (co != null) {
                if (StringUtils.hasText(co.getName()))    cName    = co.getName();
                if (StringUtils.hasText(co.getLogoUrl())) cLogo    = co.getLogoUrl();
                if (StringUtils.hasText(co.getPhone()))   cPhone   = co.getPhone();
                if (StringUtils.hasText(co.getEmail()))   cEmail   = co.getEmail();
                if (StringUtils.hasText(co.getWebsite())) cWebsite = co.getWebsite();
                if (StringUtils.hasText(co.getAddress())) cAddress = co.getAddress();
                cGst     = co.getGstin();
                cReviews = co.getTotalReviews();
                if (co.getOperatingSince() != null) {
                    cYears = Year.now().getValue() - co.getOperatingSince();
                }
            } else {
                log.debug("No Company profile for tenant {} — using configured PDF branding defaults", tenantId);
            }
        }

        // White-label: when the quotation is owned by an active sub-agent, its brand overrides the
        // tenant Company branding field-by-field (only the fields the sub-agent actually set). Works
        // on the public share-link path too — resolved off the persisted owner, not TenantContext.
        var branding = brandingService.resolve(dto.getOwnerUserId());
        if (branding.isPresent()) {
            var b = branding.get();
            if (StringUtils.hasText(b.brandName()))    cName  = b.brandName();
            if (StringUtils.hasText(b.logoUrl()))      cLogo  = b.logoUrl();
            if (StringUtils.hasText(b.contactPhone())) cPhone = b.contactPhone();
            if (StringUtils.hasText(b.contactEmail())) cEmail = b.contactEmail();
            if (StringUtils.hasText(b.brandColor()))   cColor = b.brandColor();
            // Address/website/GST/reviews stay the parent's — a sub-agent white-labels identity, not
            // the parent's legal/registration details.
            log.debug("White-label PDF branding applied for quotation {} (owner {})",
                    dto.getPublicId(), dto.getOwnerUserId());
        }

        ctx.setVariable("companyName", cName);
        ctx.setVariable("companyTagline", companyTagline);   // no dedicated Company field; configured default
        ctx.setVariable("companyPhone", cPhone);
        ctx.setVariable("companyEmail", cEmail);
        ctx.setVariable("companyWebsite", cWebsite);
        ctx.setVariable("companyAddress", cAddress);
        ctx.setVariable("companyLogoUrl", cLogo);
        ctx.setVariable("brandColor", cColor);
        ctx.setVariable("companyGst", cGst);
        ctx.setVariable("companyGoogleReviews", cReviews);
        ctx.setVariable("companyYearsExperience", cYears);

        // Which sections render, and in what order — decided ONCE, here, in Java. Templates only ask
        // "is my key in this list?", so the inclusion/emptiness rule can never drift between designs
        // the way six hand-written th:if expressions per template did. Set before the style branch
        // deliberately: it is style-agnostic, and CLASSIC simply never reads it (that template is
        // byte-frozen, and its own included-flag guards already produce the same visible result).
        ctx.setVariable("sectionOrder", orderedSectionKeys(dto));

        // The design branch. CLASSIC keeps the exact hardcoded name it has always had — same string,
        // same template file, same fonts — so a quotation that never opted in renders byte-identically.
        // Null styles (rows predating the column) resolve to CLASSIC in the mapper, so dto is never null
        // here; orDefault is belt-and-braces for callers that bypass the mapper.
        com.crm.travelcrm.quotation.enums.TemplateStyle style =
                com.crm.travelcrm.quotation.enums.TemplateStyle.orDefault(dto.getTemplateStyle());
        String templateName = switch (style) {
            case MODERN  -> "pdf/quotation-modern";
            case PREMIUM -> "pdf/quotation-premium";
            case CLASSIC -> "pdf/quotation";   // the exact hardcoded name Classic has always had
        };

        String html = templateEngine.process(templateName, ctx);
        log.debug("Thymeleaf produced XHTML for {} ({} chars, style {}); laying out PDF...",
                dto.getPublicId(), html.length(), style);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            registerStarFont(renderer);
            // All styles embed 'ModernSans' (DejaVu) — Classic's Folio redesign prices in the real
            // ₹ and uses ✓/✗ markers, so registration is unconditional across every template style.
            registerModernFont(renderer);
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            byte[] pdf = out.toByteArray();
            log.debug("PDF generated for {} | {} bytes in {} ms",
                    dto.getPublicId(), pdf.length, (System.nanoTime() - startNanos) / 1_000_000);
            return pdf;
        } catch (Exception ex) {
            // The cause stays in the log. Concatenating ex.getMessage() into a BusinessException
            // would ship it straight to the client, since that handler echoes the message verbatim —
            // and here the cause is renderer internals (iText/font/resource paths).
            log.error("Failed to render quotation PDF for {}: {}",
                    dto.getPublicId(), ex.getMessage(), ex);
            throw new BusinessException("We couldn't generate the quotation PDF. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The section manifest handed to the templates as {@code sectionOrder}: which of the six sections
     * actually render, in the order they render.
     *
     * <p><b>Order</b> — the originating lead's chosen sections first, in the order that lead listed
     * them (mirroring how the builder puts the customer's chosen services in front), then everything
     * else in {@link QuotationSection} declaration order. A quotation with no lead snapshot gets the
     * pure canonical order.
     *
     * <p><b>Inclusion</b> — a section survives only if it was explicitly included AND has something
     * to show. "Included but empty" is the case that produced a heading with nothing under it. ADDON
     * needs one more check: its items carry their OWN {@code included} flag, and an add-on block whose
     * every line is switched off is just as empty as one with no lines.
     *
     * <p>Derived purely from the DTO — no repository, no {@link TenantContext}. The public share-link
     * path renders with neither, and this must behave identically there.
     */
    private static List<String> orderedSectionKeys(QuotationResponseDto dto) {
        List<String> chosen = dto.getAllowedServices() != null ? dto.getAllowedServices() : List.of();

        List<QuotationSection> ordered = new ArrayList<>();
        for (String key : chosen) {
            for (QuotationSection s : QuotationSection.values()) {
                if (s.key().equals(key) && !ordered.contains(s)) ordered.add(s);
            }
        }
        for (QuotationSection s : QuotationSection.values()) {
            if (!ordered.contains(s)) ordered.add(s);
        }

        List<String> keys = new ArrayList<>();
        for (QuotationSection s : ordered) {
            if (rendersSomething(dto, s)) keys.add(s.key());
        }
        log.debug("sectionOrder for {} -> {} (lead snapshot: {})", dto.getPublicId(), keys, chosen);
        return keys;
    }

    /**
     * The one rule, written once: a section renders iff it is <b>included</b> AND it has something
     * to show — either rows, or money.
     *
     * <p>The {@code || carries money} half is not a nicety, it keeps the document honest. Totals are
     * summed from the six {@code *Amount} scalars ({@code QuotationMapper.computeTotals}), which do
     * not consult the row lists. Hiding a section on emptiness alone would therefore drop a
     * <em>lump-sum</em> section — "flights quoted separately, ₹50,000, no segments entered" is a real
     * way agents use this — off the page while its money stayed in the grand total, handing the
     * customer a total they cannot derive from anything printed. Excluded sections are already
     * zeroed on the write path, so an included section carrying an amount is always intentional.
     *
     * @see #orderedSectionKeys
     */
    private static boolean rendersSomething(QuotationResponseDto dto, QuotationSection section) {
        return switch (section) {
            case FLIGHT -> dto.getFlight() != null
                    && Boolean.TRUE.equals(dto.getFlight().getIncluded())
                    && (notEmpty(dto.getFlight().getSegments()) || carriesMoney(dto.getFlight().getAmount()));
            case HOTEL -> dto.getHotel() != null
                    && Boolean.TRUE.equals(dto.getHotel().getIncluded())
                    && (notEmpty(dto.getHotel().getHotels()) || carriesMoney(dto.getHotel().getAmount()));
            case SIGHTSEEING -> dto.getSightseeing() != null
                    && Boolean.TRUE.equals(dto.getSightseeing().getIncluded())
                    && (notEmpty(dto.getSightseeing().getDays()) || carriesMoney(dto.getSightseeing().getAmount()));
            case CRUISE -> dto.getCruise() != null
                    && Boolean.TRUE.equals(dto.getCruise().getIncluded())
                    && (notEmpty(dto.getCruise().getCruises()) || carriesMoney(dto.getCruise().getAmount()));
            case VEHICLE -> dto.getVehicle() != null
                    && Boolean.TRUE.equals(dto.getVehicle().getIncluded())
                    && (notEmpty(dto.getVehicle().getVehicles()) || carriesMoney(dto.getVehicle().getAmount()));
            // A null item-level flag means "included" (only an explicit false switches a line off),
            // which is how the builder writes rows it never asked the user about.
            case ADDON -> dto.getAddons() != null
                    && Boolean.TRUE.equals(dto.getAddons().getIncluded())
                    && (carriesMoney(dto.getAddons().getAmount())
                        || (notEmpty(dto.getAddons().getItems())
                            && dto.getAddons().getItems().stream()
                                    .anyMatch(i -> !Boolean.FALSE.equals(i.getIncluded()))));
        };
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /** Non-null and non-zero — {@code signum()} so 0.00 and 0 both read as "no money". */
    private static boolean carriesMoney(BigDecimal amount) {
        return amount != null && amount.signum() != 0;
    }

    /**
     * Copies the bundled {@value #STAR_FONT_RESOURCE} out of the classpath to a temp file so the
     * OpenPDF font resolver (which reads fonts from a filesystem path) can load it in every
     * environment — exploded {@code target/classes} in dev and inside the packaged JAR in prod.
     * Never throws: on any failure it logs and returns {@code null}, and the PDF still renders
     * (only the ★ glyph is missing) rather than the whole export failing.
     */
    private static File extractStarFont() {
        try (InputStream in = new ClassPathResource(STAR_FONT_RESOURCE).getInputStream()) {
            File tmp = File.createTempFile("quotation-star-font-", ".ttf");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.debug("Star-rating font extracted to {}", tmp.getAbsolutePath());
            return tmp;
        } catch (Exception ex) {
            log.warn("Could not load bundled star font '{}' — PDF star ratings will be blank: {}",
                    STAR_FONT_RESOURCE, ex.getMessage());
            return null;
        }
    }

    /**
     * Registers the bundled DejaVu Sans (embedded, IDENTITY_H) under the private
     * {@value #STAR_FONT_FAMILY} family used only by the template's star span. Registration is
     * per-renderer (each {@link ITextRenderer} owns its own font resolver) and best-effort — a
     * failure just leaves the star span without a glyph, it never aborts the render.
     */
    private void registerStarFont(ITextRenderer renderer) {
        if (starFontFile == null) return;
        try {
            renderer.getFontResolver().addFont(
                    starFontFile.getAbsolutePath(), STAR_FONT_FAMILY,
                    BaseFont.IDENTITY_H, BaseFont.EMBEDDED, null);
        } catch (Exception ex) {
            log.warn("Could not register star font with the PDF renderer: {}", ex.getMessage());
        }
    }

    /**
     * The MODERN template's body font — the same bundled DejaVu Sans, registered under the family name
     * the Modern stylesheet uses ({@code ModernSans}).
     *
     * <p>Why this matters beyond looks: DejaVu Sans carries U+20B9, so the Modern design can print a
     * real ₹ where Classic must print "Rs." ({@code PdfFormat.inr}'s javadoc documents that base-14
     * Helvetica lacks the glyph). Registered ONLY for Modern renders: Classic's stylesheet never
     * references this family, but keeping the registration out of its path entirely means Classic's
     * font resolution cannot change even in theory — that is the zero-regression stance.
     *
     * <p>To upgrade Modern to a premium editorial face later: drop the TTF into
     * {@code src/main/resources/fonts/}, extract it exactly like {@link #extractStarFont()}, and
     * register it here under the same family name. Nothing else changes.
     */
    /**
     * The Classic template's bundled artwork (cover background + running-page banner), extracted from
     * the classpath to temp files at construction — the same materialisation the star font needs, for
     * the same reason: the renderer resolves {@code url(...)} against the filesystem, not the jar.
     *
     * <p><b>This replaces hardcoded {@code file:///C:/travelcrmbackend/...} paths that only ever
     * existed on one dev machine.</b> In every packaged jar — i.e. production — those paths resolved to
     * nothing and every customer-facing Classic PDF shipped without its cover art and header banner,
     * silently, while the same render on that one machine looked perfect. The template now takes these
     * as context variables ({@code travelBgUrl}/{@code bannerUrl}); null degrades to the template's
     * solid-colour fallback, exactly the (broken) output prod produced before this fix.
     */
    private final File travelBgFile = extractPdfResource("templates/pdf/travel.png", ".png");
    private final File bannerFile   = extractPdfResource("templates/pdf/banner.png", ".png");

    /** Classpath → temp file, non-fatal. Null on failure — the render proceeds without the image. */
    private static File extractPdfResource(String resource, String suffix) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            File tmp = File.createTempFile("quotation-pdf-asset-", suffix);
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (Exception ex) {
            log.warn("Could not extract bundled PDF asset '{}' — rendering without it: {}",
                    resource, ex.getMessage());
            return null;
        }
    }

    /** File → the {@code file:/} URI the template's url() needs, or null to trigger the CSS fallback. */
    private static String toUriOrNull(File f) {
        return f == null ? null : f.toURI().toString();
    }

    private void registerModernFont(ITextRenderer renderer) {
        if (starFontFile == null) return;   // same extracted TTF; same non-fatal posture
        try {
            renderer.getFontResolver().addFont(
                    starFontFile.getAbsolutePath(), "ModernSans",
                    BaseFont.IDENTITY_H, BaseFont.EMBEDDED, null);
        } catch (Exception ex) {
            log.warn("Could not register the Modern body font with the PDF renderer: {}", ex.getMessage());
        }
    }
}