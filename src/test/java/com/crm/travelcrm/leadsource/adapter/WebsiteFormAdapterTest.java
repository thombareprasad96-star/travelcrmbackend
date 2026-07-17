package com.crm.travelcrm.leadsource.adapter;

import com.crm.travelcrm.leadsource.gateway.HttpRawInbound;
import com.crm.travelcrm.leadsource.spi.InboundParseResult;
import com.crm.travelcrm.leadsource.spi.InboundVerification;
import com.crm.travelcrm.leadsource.spi.IntegrationCredentials;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.NormalizedLead;
import com.crm.travelcrm.leadsource.spi.RawInbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference adapter's contract.
 *
 * <p>These are plain unit tests over {@code byte[]} fixtures with no Spring and no database — which is
 * itself the point being demonstrated: an adapter is pure, so testing one costs nothing.
 */
class WebsiteFormAdapterTest {

    private final WebsiteFormAdapter adapter = new WebsiteFormAdapter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RawInbound body(String raw) {
        return new HttpRawInbound(raw.getBytes(StandardCharsets.UTF_8), Map.of(), Map.of(),
                IntegrationCredentials.empty(), objectMapper);
    }

    private NormalizedLead onlyLead(InboundParseResult result) {
        assertThat(result).isInstanceOf(InboundParseResult.Complete.class);
        var leads = ((InboundParseResult.Complete) result).leads();
        assertThat(leads).hasSize(1);
        return leads.get(0);
    }

    @Nested
    @DisplayName("accepts both body shapes a real embed produces")
    class BodyShapes {

        @Test
        void parsesJson() {
            NormalizedLead lead = onlyLead(adapter.parse(body("""
                    {"name":"Asha Rao","phone":"+91 98765 43210","email":"Asha@Example.com",
                     "message":"Goa package for 4"}""")));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
            assertThat(lead.phoneRaw()).isEqualTo("+91 98765 43210");
            assertThat(lead.email()).isEqualTo("Asha@Example.com");
            assertThat(lead.message()).isEqualTo("Goa package for 4");
        }

        /**
         * A plain HTML {@code <form>} posts this. Supporting only JSON would mean the simplest
         * integration — the one a non-technical tenant can actually do — silently fails.
         */
        @Test
        void parsesFormEncoded() {
            NormalizedLead lead = onlyLead(adapter.parse(
                    body("name=Asha+Rao&phone=%2B919876543210&message=Goa+package")));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
            assertThat(lead.phoneRaw()).isEqualTo("+919876543210");
            assertThat(lead.message()).isEqualTo("Goa package");
        }

        @Test
        void acceptsTheCommonFieldNameAliases() {
            NormalizedLead lead = onlyLead(adapter.parse(
                    body("{\"fullName\":\"Asha\",\"mobile\":\"9876543210\",\"enquiry\":\"Kerala\"}")));

            assertThat(lead.customerName()).isEqualTo("Asha");
            assertThat(lead.phoneRaw()).isEqualTo("9876543210");
            assertThat(lead.message()).isEqualTo("Kerala");
        }
    }

    @Nested
    @DisplayName("routine non-lead traffic is IGNORED, never thrown")
    class NonLeadTraffic {

        /**
         * Throwing would turn a bot or a broken embed into a provider retry storm and an alert nobody
         * can action.
         */
        @Test
        void aPostWithNoContactDetailsIsIgnored() {
            InboundParseResult result = adapter.parse(body("{\"message\":\"hello\"}"));

            assertThat(result).isInstanceOf(InboundParseResult.Ignored.class);
            assertThat(((InboundParseResult.Ignored) result).reason()).isEqualTo("no_contact_details");
        }

        @Test
        void anEmptyBodyIsIgnored() {
            assertThat(adapter.parse(body(""))).isInstanceOf(InboundParseResult.Ignored.class);
        }

        @Test
        void garbageIsIgnoredRatherThanThrowing() {
            assertThat(adapter.parse(body("<html>404</html>")))
                    .isInstanceOf(InboundParseResult.Ignored.class);
        }

        @Test
        void aPhoneAloneIsEnoughToBeALead() {
            assertThat(adapter.parse(body("{\"phone\":\"9876543210\"}")))
                    .isInstanceOf(InboundParseResult.Complete.class);
        }

        @Test
        void anEmailAloneIsEnoughToBeALead() {
            assertThat(adapter.parse(body("{\"email\":\"a@b.com\"}")))
                    .isInstanceOf(InboundParseResult.Complete.class);
        }
    }

    @Nested
    @DisplayName("the SPI contract")
    class SpiContract {

        /**
         * The single most important assertion in this file. Three phone treatments already coexist in
         * this codebase and disagree; an adapter canonicalising here would add a fourth, and sixteen
         * adapters would each add it differently. The framework canonicalises exactly once.
         */
        @Test
        void phoneIsPassedThroughUNTOUCHED() {
            NormalizedLead lead = onlyLead(adapter.parse(body("{\"phone\":\" 098765 43210 \"}")));

            assertThat(lead.phoneRaw()).isEqualTo("098765 43210");   // trimmed by field read only
            assertThat(lead.phoneRaw()).doesNotStartWith("+91");     // NOT canonicalised
        }

        @Test
        void declaresTokenOnlyVerificationExplicitly() {
            // A website has no shared secret — anything embedded in a public page is public. This must
            // be a written statement, not an inherited default.
            assertThat(adapter.verification()).isInstanceOf(InboundVerification.TokenOnly.class);
        }

        @Test
        void servesTheWebsiteFormChannel() {
            assertThat(adapter.channel()).isEqualTo(LeadSourceChannel.WEBSITE_FORM);
        }

        @Test
        void needsNoCredentialsToConnect() {
            assertThat(adapter.catalog().requiredCredentialKeys()).isEmpty();
        }

        @Test
        void redactsNothingBecauseItCarriesNoSecret() {
            assertThat(adapter.secretFieldPaths()).isEmpty();
        }
    }
}
