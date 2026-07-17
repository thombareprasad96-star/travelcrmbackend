package com.crm.travelcrm.leadsource.adapter;

import com.crm.travelcrm.leadsource.spi.ChannelCatalogEntry;
import com.crm.travelcrm.leadsource.spi.InboundParseResult;
import com.crm.travelcrm.leadsource.spi.InboundVerification;
import com.crm.travelcrm.leadsource.spi.LeadSourceAdapter;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.NormalizedLead;
import com.crm.travelcrm.leadsource.spi.RawInbound;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The tenant's own website contact form.
 *
 * <p><b>This is the framework's proof of the "adding a channel = writing one adapter" claim</b>, and
 * the reference every other adapter should be read against: it is one class, it holds no state, it
 * touches no database, and the framework does the rest.
 *
 * <p>It is deliberately the FIRST channel: the payload shape is ours, so it can ship without waiting
 * on any provider's documentation. Every other channel's field names are a research task, and
 * inventing them would produce an adapter that compiles, passes its tests against fixtures we made up,
 * and fails on first contact with the real provider.
 *
 * <p><b>Accepts both JSON and form-encoded bodies.</b> A tenant embedding a plain HTML {@code <form>}
 * posts {@code application/x-www-form-urlencoded}; one wiring it up with {@code fetch} posts JSON.
 * Supporting only JSON would mean the simplest possible integration — the one a non-technical tenant
 * can actually do — silently fails.
 */
@Component
public class WebsiteFormAdapter implements LeadSourceAdapter {

    // Accepted aliases per field. A website form's input names are the TENANT'S choice, and telling
    // them to rename their form is a worse product than accepting the obvious spellings.
    private static final List<String> NAME_KEYS =
            List.of("name", "customerName", "customer_name", "fullName", "full_name", "yourName");
    private static final List<String> PHONE_KEYS =
            List.of("phone", "mobile", "phoneNumber", "phone_number", "contact", "contactNumber");
    private static final List<String> EMAIL_KEYS =
            List.of("email", "emailAddress", "email_address", "mail");
    private static final List<String> MESSAGE_KEYS =
            List.of("message", "enquiry", "comments", "notes", "details", "requirement");

    @Override
    public LeadSourceChannel channel() {
        return LeadSourceChannel.WEBSITE_FORM;
    }

    /**
     * The URL's ingest token is the whole authentication, and this is a deliberate statement rather
     * than an inherited default.
     *
     * <p>A tenant's website has no shared secret to sign with: anything embedded in a public page is
     * public. Whoever holds the endpoint URL can post leads to it — which is precisely what the form
     * is FOR. The exposure is spam, not cross-tenant leakage, and spam is bounded by the rate limit and
     * cleaned up by the tenant.
     */
    @Override
    public InboundVerification verification() {
        return new InboundVerification.TokenOnly();
    }

    @Override
    public InboundParseResult parse(RawInbound in) {
        Map<String, String> fields = readFields(in);

        String phone = firstNonBlank(fields, PHONE_KEYS);
        String email = firstNonBlank(fields, EMAIL_KEYS);

        // A form post with neither is a bot, a health check, or a broken embed — all routine, none of
        // them a lead. Ignored, not thrown: throwing would turn routine traffic into a retry storm and
        // an alert nobody can action.
        if (isBlank(phone) && isBlank(email)) {
            return new InboundParseResult.Ignored("no_contact_details");
        }

        // Phone passes through UNTOUCHED. The framework canonicalises exactly once — an adapter doing
        // it here would be the fourth phone treatment in this codebase.
        return new InboundParseResult.Complete(List.of(NormalizedLead.builder()
                .customerName(firstNonBlank(fields, NAME_KEYS))
                .phoneRaw(phone)
                .email(email)
                .message(firstNonBlank(fields, MESSAGE_KEYS))
                .extras(Map.of())
                .build()));
    }

    /** JSON if the body parses as a flat object, otherwise form-encoded. */
    private Map<String, String> readFields(RawInbound in) {
        JsonNode json = in.json();
        if (json != null && json.isObject()) {
            Map<String, String> out = new java.util.HashMap<>();
            json.fields().forEachRemaining(e -> {
                if (e.getValue() != null && e.getValue().isValueNode()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
            return out;
        }
        return in.form();
    }

    private String firstNonBlank(Map<String, String> fields, List<String> keys) {
        for (String key : keys) {
            String v = fields.get(key);
            if (!isBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public ChannelCatalogEntry catalog() {
        return ChannelCatalogEntry.builder()
                .displayName("Website Form")
                .description("Receive enquiries straight from your own website's contact form.")
                .credentialFields(List.of())   // nothing to authenticate with — the URL token is it
                .configFields(List.of(new ChannelCatalogEntry.ConfigField(
                        "allowedOrigins", "Allowed website addresses", false,
                        "Comma-separated, e.g. https://yoursite.com. Leave blank to accept from "
                                + "anywhere.")))
                .setupHint("Post your form to the URL below. It accepts a normal HTML form post or "
                        + "JSON, with fields named name, phone, email and message.")
                .webhookUrlShown(true)
                .build();
    }
}
