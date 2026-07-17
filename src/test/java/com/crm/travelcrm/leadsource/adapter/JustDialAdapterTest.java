package com.crm.travelcrm.leadsource.adapter;

import com.crm.travelcrm.leadsource.gateway.HttpRawInbound;
import com.crm.travelcrm.leadsource.spi.IdempotencyKey;
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
 * The JustDial adapter's contract.
 *
 * <p><b>What these tests are, and are not.</b> They are NOT evidence that the field names are right —
 * they are fixtures we wrote, so they can only ever confirm we parse what we already assumed. The
 * adapter's javadoc explains why the assumed names are unverified (their only public source traces to a
 * domain that does not exist). Testing against our own guess and calling it green is exactly the trap
 * the design doc warns about.
 *
 * <p>What they DO pin down is everything that is true regardless of the field names, and that is most
 * of the risk:
 * <ul>
 *   <li>every body shape is accepted — above all the bodiless GET, whose absence would have made the
 *       channel silently receive nothing;</li>
 *   <li>unknown or unparseable traffic is IGNORED, never thrown — no retry storm;</li>
 *   <li>the phone is passed through untouched, and JustDial's retry is deduplicated.</li>
 * </ul>
 */
class JustDialAdapterTest {

    private final JustDialAdapter adapter = new JustDialAdapter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RawInbound body(String raw) {
        return new HttpRawInbound(raw.getBytes(StandardCharsets.UTF_8), Map.of(), Map.of(),
                IntegrationCredentials.empty(), objectMapper);
    }

    /** A bodiless GET carrying the lead in the query string. */
    private RawInbound query(Map<String, String> params) {
        return new HttpRawInbound(new byte[0], Map.of(), params,
                IntegrationCredentials.empty(), objectMapper);
    }

    private NormalizedLead onlyLead(InboundParseResult result) {
        assertThat(result).isInstanceOf(InboundParseResult.Complete.class);
        var leads = ((InboundParseResult.Complete) result).leads();
        assertThat(leads).hasSize(1);
        return leads.get(0);
    }

    @Nested
    @DisplayName("accepts every shape the push might take")
    class BodyShapes {

        /**
         * <b>The most important test in this file.</b> A bodiless GET push is the most credible
         * hypothesis for JustDial, and an adapter that only read bodies would parse every real lead as
         * zero leads — while the connection reported itself perfectly healthy. Silent, and indefinitely
         * so.
         */
        @Test
        void parsesAGetPushCarryingTheLeadInTheQueryString() {
            NormalizedLead lead = onlyLead(adapter.parse(query(Map.of(
                    "leadid", "884422",
                    "name", "Asha Rao",
                    "mobile", "9876543210",
                    "category", "Travel Agents",
                    "city", "Pune"))));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
            assertThat(lead.phoneRaw()).isEqualTo("9876543210");
            assertThat(lead.message()).contains("Travel Agents").contains("Pune");
        }

        @Test
        void parsesAJsonPush() {
            NormalizedLead lead = onlyLead(adapter.parse(body("""
                    {"leadid":"884422","name":"Asha Rao","mobile":"9876543210",
                     "email":"asha@example.com","category":"Travel Agents","city":"Pune"}""")));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
            assertThat(lead.phoneRaw()).isEqualTo("9876543210");
            assertThat(lead.email()).isEqualTo("asha@example.com");
        }

        @Test
        void parsesAFormEncodedPush() {
            NormalizedLead lead = onlyLead(adapter.parse(
                    body("leadid=884422&name=Asha+Rao&mobile=9876543210&city=Pune")));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
            assertThat(lead.phoneRaw()).isEqualTo("9876543210");
        }

        /** One delivery, several enquiries — why Complete takes a list. */
        @Test
        void parsesABatchOfLeads() {
            InboundParseResult result = adapter.parse(body("""
                    {"leads":[{"leadid":"1","name":"A","mobile":"9111111111"},
                              {"leadid":"2","name":"B","mobile":"9222222222"}]}"""));

            assertThat(((InboundParseResult.Complete) result).leads()).hasSize(2);
        }

        @Test
        void parsesATopLevelArray() {
            InboundParseResult result = adapter.parse(
                    body("[{\"name\":\"A\",\"mobile\":\"9111111111\"}]"));

            assertThat(((InboundParseResult.Complete) result).leads()).hasSize(1);
        }

        /** The field names are a guess, so the case they arrive in is a guess too. */
        @Test
        void isCaseInsensitiveAboutFieldNames() {
            NormalizedLead lead = onlyLead(adapter.parse(
                    body("{\"LeadId\":\"9\",\"NAME\":\"Asha\",\"Mobile\":\"9876543210\"}")));

            assertThat(lead.customerName()).isEqualTo("Asha");
            assertThat(lead.phoneRaw()).isEqualTo("9876543210");
        }
    }

    @Nested
    @DisplayName("never turns a misread into a retry storm")
    class NonLeadTraffic {

        /**
         * The failure mode that matters if the guessed names are wrong. JustDial disables a notification
         * URL that keeps erroring — so throwing here would lose every FUTURE lead, not just this one.
         * The raw payload is persisted regardless, which is what makes the miss discoverable.
         */
        @Test
        void anUnrecognisedShapeIsIgnoredRatherThanThrown() {
            InboundParseResult result = adapter.parse(body("{\"someUnknownField\":\"whatever\"}"));

            assertThat(result).isInstanceOf(InboundParseResult.Ignored.class);
            assertThat(((InboundParseResult.Ignored) result).reason()).isEqualTo("no_contact_details");
        }

        @Test
        void anEmptyDeliveryIsIgnored() {
            assertThat(adapter.parse(body(""))).isInstanceOf(InboundParseResult.Ignored.class);
        }

        @Test
        void garbageIsIgnoredRatherThanThrowing() {
            assertThat(adapter.parse(body("<html>404</html>")))
                    .isInstanceOf(InboundParseResult.Ignored.class);
        }

        @Test
        void aLiteralNullStringIsNotAPhoneNumber() {
            // Providers send "null" as text far more often than they omit the key.
            assertThat(adapter.parse(body("{\"mobile\":\"null\",\"email\":\"null\"}")))
                    .isInstanceOf(InboundParseResult.Ignored.class);
        }

        @Test
        void aPhoneAloneIsEnoughToBeALead() {
            assertThat(adapter.parse(body("{\"mobile\":\"9876543210\"}")))
                    .isInstanceOf(InboundParseResult.Complete.class);
        }
    }

    @Nested
    @DisplayName("the SPI contract")
    class SpiContract {

        /**
         * Three phone treatments already coexist in this codebase and disagree. The framework
         * canonicalises exactly once; an adapter doing it here would be the fourth.
         */
        @Test
        void phoneIsPassedThroughUNTOUCHED() {
            NormalizedLead lead = onlyLead(adapter.parse(body("{\"mobile\":\" 098765 43210 \"}")));

            assertThat(lead.phoneRaw()).isEqualTo("098765 43210");
            assertThat(lead.phoneRaw()).doesNotStartWith("+91");
        }

        /** A hint, not a rewrite — JustDial is India-only, but PhoneCanonicalizer still decides. */
        @Test
        void hintsIndiaWithoutRewritingTheNumber() {
            assertThat(onlyLead(adapter.parse(body("{\"mobile\":\"9876543210\"}"))).phoneCountryHint())
                    .isEqualTo("+91");
        }

        /** JustDial retries. Without a dedup key a retry silently becomes a second lead. */
        @Test
        void dedupesOnJustDialsOwnLeadId() {
            assertThat(adapter.dedupKey(body("{\"leadid\":\"884422\",\"mobile\":\"9876543210\"}")))
                    .isEqualTo(IdempotencyKey.of("884422"));
        }

        /**
         * Hashing the body instead would treat two genuine enquiries from the same person for the same
         * category as one, and silently discard the second — losing a real lead to prevent a duplicate.
         */
        @Test
        void offersNoDedupKeyRatherThanInventingOne() {
            assertThat(adapter.dedupKey(body("{\"mobile\":\"9876543210\"}")).isPresent()).isFalse();
        }

        @Test
        void servesTheJustDialChannel() {
            assertThat(adapter.channel()).isEqualTo(LeadSourceChannel.JUSTDIAL);
        }

        /**
         * JustDial has no documented signature, so the URL token is the whole boundary — and that must be
         * a written statement, never an inherited default.
         *
         * <p>If a real delivery proves a secret rides in the body, this changes to
         * {@code SharedSecretInBody} AND {@code secretFieldPaths()} must gain the same path in the same
         * edit — else a live credential is persisted next to PII.
         */
        @Test
        void declaresTokenOnlyVerificationExplicitly() {
            assertThat(adapter.verification()).isInstanceOf(InboundVerification.TokenOnly.class);
        }

        @Test
        void redactsNothingBecauseNoSecretRidesInTheBody() {
            assertThat(adapter.secretFieldPaths()).isEmpty();
        }

        /** Connecting is copy-the-URL-and-email-it: there is nothing for JustDial to authenticate with. */
        @Test
        void needsNoCredentialsToConnect() {
            assertThat(adapter.catalog().requiredCredentialKeys()).isEmpty();
        }

        /**
         * No attribution: JustDial is a marketplace listing, not a campaign. Synthesising one would put
         * fiction into the attribution reports.
         */
        @Test
        void claimsNoCampaignAttribution() {
            assertThat(onlyLead(adapter.parse(body("{\"mobile\":\"9876543210\"}"))).attribution()).isNull();
        }

        /** Compliance evidence must survive even though nothing acts on it yet. */
        @Test
        void keepsTheDncFlagAsDiagnosticEvidence() {
            NormalizedLead lead = onlyLead(adapter.parse(
                    body("{\"mobile\":\"9876543210\",\"dncmobile\":\"1\"}")));

            assertThat(lead.extras()).containsEntry("dncMobile", "1");
        }
    }
}
