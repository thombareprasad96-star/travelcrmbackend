package com.crm.travelcrm.leadsource.adapter;

import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.leadsource.gateway.HttpRawInbound;
import com.crm.travelcrm.leadsource.gateway.InboundVerifier;
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
 * The inbound-WhatsApp adapter's contract.
 *
 * <p>The envelope fixtures follow Interakt's published example. The signature test is stronger than a
 * fixture: it runs the framework's real verifier against <b>Interakt's own published worked example</b>,
 * so it would catch a wrong prefix or encoding rather than restating our assumption.
 */
class WhatsAppInboundAdapterTest {

    private final WhatsAppInboundAdapter adapter = new WhatsAppInboundAdapter();
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

    /** Interakt's documented inbound shape. */
    private static final String INBOUND = """
            {
              "version":"1.0",
              "timestamp":"2022-06-03T05:57:57.496889",
              "type":"message_received",
              "data":{
                "customer":{
                  "id":"52918eb3-bd00-4331-a51d-c4dcffee48d6",
                  "channel_phone_number":"917003705584",
                  "traits":{"name":"Asha Rao","User Id":"111"}
                },
                "message":{
                  "id":"msg-88",
                  "chat_message_type":"CustomerMessage",
                  "message_content_type":"Text",
                  "message":"bhaiya Nepal package batao"
                }
              }
            }""";

    /** The same, plus Meta's referral — the shape IF Interakt forwards it. */
    private static final String INBOUND_FROM_AD = """
            {
              "type":"message_received",
              "data":{
                "customer":{"channel_phone_number":"917003705584","traits":{"name":"Asha Rao"}},
                "message":{
                  "id":"msg-99",
                  "chat_message_type":"CustomerMessage",
                  "message":"Nepal package?",
                  "referral":{
                    "source_url":"https://fb.me/3cr4Wqqkv",
                    "source_id":"120226305854810726",
                    "source_type":"ad",
                    "headline":"Nepal tours from Gorakhpur",
                    "body":"Book now, EMI available",
                    "media_type":"image",
                    "ctwa_clid":"AfeCTWA123",
                    "welcome_message":{"text":"Hi! Tell us your dates."}
                  }
                }
              }
            }""";

    @Nested
    @DisplayName("WhatsApp is a funnel — the source must reflect what the payload PROVES")
    class FunnelNotSource {

        /**
         * <b>The single most important test in this file.</b> Without the referral we genuinely do not
         * know where this lead came from — an Instagram bio, a Google listing, a referral. WHATSAPP here
         * means "arrived through the WhatsApp funnel, TRUE SOURCE UNKNOWN", and it must stay that way:
         * an unattributed lead has to look unattributed.
         */
        @Test
        void aPlainMessageDoesNotClaimASourceItCannotKnow() {
            NormalizedLead lead = onlyLead(adapter.parse(body(INBOUND)));

            assertThat(lead.sourceOverride()).isNull();   // ⇒ the pipeline stamps the channel's WHATSAPP
            assertThat(lead.attribution()).isNull();      // no evidence ⇒ no attribution row
        }

        /**
         * A Click-to-WhatsApp ad DID drive this one, and the payload proves it. Stamping WHATSAPP would
         * bury the Meta spend in a bucket named after the medium it travelled over.
         */
        @Test
        void aClickToWhatsAppAdIsAttributedToMetaNotToWhatsApp() {
            NormalizedLead lead = onlyLead(adapter.parse(body(INBOUND_FROM_AD)));

            assertThat(lead.sourceOverride()).isEqualTo(LeadSource.META_ADS);
            assertThat(lead.attribution().adId()).isEqualTo("120226305854810726");
        }

        /**
         * ctwa_clid is OMITTED for WhatsApp Status ad placements — so a null click id does NOT mean "not
         * from an ad". Gating on it would silently mis-attribute every Status-placement lead.
         */
        @Test
        void anAdWithNoClickIdIsStillAnAd() {
            NormalizedLead lead = onlyLead(adapter.parse(body("""
                    {"type":"message_received","data":{
                      "customer":{"channel_phone_number":"917003705584"},
                      "message":{"id":"m1","chat_message_type":"CustomerMessage","message":"hi",
                                 "referral":{"source_id":"120226305854810726","source_type":"ad"}}}}""")));

            assertThat(lead.sourceOverride()).isEqualTo(LeadSource.META_ADS);
        }

        /**
         * source_id is the AD id, never a campaign id. Filling campaignId with it would quietly corrupt
         * every campaign report — reaching campaign grain needs a separate Marketing API lookup.
         */
        @Test
        void doesNotPretendAnAdIdIsACampaignId() {
            NormalizedLead lead = onlyLead(adapter.parse(body(INBOUND_FROM_AD)));

            assertThat(lead.attribution().campaignId()).isNull();
            assertThat(lead.attribution().adSetId()).isNull();
        }

        /** The join key for reporting a booking back to Meta later. Losing it now is unrecoverable. */
        @Test
        void keepsTheClickIdForConversionReporting() {
            assertThat(onlyLead(adapter.parse(body(INBOUND_FROM_AD))).extras())
                    .containsEntry("ctwaClid", "AfeCTWA123");
        }

        /**
         * referral.body is the AD's primary text; the customer typed something else entirely. Collapsing
         * them would put marketing copy into the lead's notes as if the customer had written it.
         */
        @Test
        void neverConfusesAdCopyWithWhatTheCustomerTyped() {
            NormalizedLead lead = onlyLead(adapter.parse(body(INBOUND_FROM_AD)));

            assertThat(lead.message()).isEqualTo("Nepal package?");
            assertThat(lead.message()).doesNotContain("EMI available");
            assertThat(lead.extras()).containsEntry("adBody", "Book now, EMI available");
        }
    }

    @Nested
    @DisplayName("Interakt's envelope")
    class Envelope {

        @Test
        void parsesAnInboundCustomerMessage() {
            NormalizedLead lead = onlyLead(adapter.parse(body(INBOUND)));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
            assertThat(lead.phoneRaw()).isEqualTo("917003705584");
            assertThat(lead.message()).isEqualTo("bhaiya Nepal package batao");
        }

        /** Interakt's 3-second SLA means a slow reply gets retried — this is what stops a double lead. */
        @Test
        void dedupesOnInteraktsMessageId() {
            assertThat(adapter.dedupKey(body(INBOUND))).isEqualTo(IdempotencyKey.of("msg-88"));
        }

        /** WhatsApp carries no email. Synthesizing one would collide on the second enquiry. */
        @Test
        void neverInventsAnEmail() {
            assertThat(onlyLead(adapter.parse(body(INBOUND))).email()).isNull();
        }

        /** traits is a tenant-configurable bag — name is not guaranteed. The pipeline falls back. */
        @Test
        void toleratesAMissingNameAndUnknownTraits() {
            NormalizedLead lead = onlyLead(adapter.parse(body("""
                    {"type":"message_received","data":{
                      "customer":{"channel_phone_number":"917003705584","traits":{"Loyalty Tier":"gold"}},
                      "message":{"id":"m1","chat_message_type":"CustomerMessage","message":"hi"}}}""")));

            assertThat(lead.customerName()).isNull();
            assertThat(lead.phoneRaw()).isEqualTo("917003705584");
        }

        /** E.164 digits, no '+', no "whatsapp:" prefix. The framework canonicalises exactly once. */
        @Test
        void passesThePhoneThroughUntouched() {
            assertThat(onlyLead(adapter.parse(body(INBOUND))).phoneRaw()).isEqualTo("917003705584");
        }
    }

    @Nested
    @DisplayName("traffic that must not become a lead")
    class NonLeadTraffic {

        /**
         * Interakt posts our OWN outbound messages to this same URL. Without the discriminator the
         * agency's every reply would create a lead from itself.
         */
        @Test
        void ourOwnOutboundEchoIsIgnored() {
            InboundParseResult result = adapter.parse(body("""
                    {"type":"message_received","data":{
                      "customer":{"channel_phone_number":"917003705584"},
                      "message":{"id":"m2","chat_message_type":"UserMessage","message":"Sure, sending now"}}}"""));

            assertThat(result).isInstanceOf(InboundParseResult.Ignored.class);
            assertThat(((InboundParseResult.Ignored) result).reason()).isEqualTo("not_a_customer_message");
        }

        /** Interakt dispatches by a body field, and this URL also receives delivery-status callbacks. */
        @Test
        void aStatusCallbackIsIgnoredRatherThanThrown() {
            InboundParseResult result = adapter.parse(body(
                    "{\"type\":\"message_api_sent\",\"data\":{\"message\":{\"id\":\"m3\"}}}"));

            assertThat(result).isInstanceOf(InboundParseResult.Ignored.class);
            assertThat(((InboundParseResult.Ignored) result).reason()).isEqualTo("not_an_inbound_message");
        }

        @Test
        void garbageIsIgnoredRatherThanThrowing() {
            assertThat(adapter.parse(body("<html>nope</html>")))
                    .isInstanceOf(InboundParseResult.Ignored.class);
        }
    }

    @Nested
    @DisplayName("the SPI contract")
    class SpiContract {

        @Test
        void servesTheWhatsAppChannel() {
            assertThat(adapter.channel()).isEqualTo(LeadSourceChannel.WHATSAPP);
        }

        @Test
        void declaresInteraktsHmacHeader() {
            InboundVerification v = adapter.verification();

            assertThat(v).isInstanceOf(InboundVerification.HmacHeader.class);
            InboundVerification.HmacHeader h = (InboundVerification.HmacHeader) v;
            assertThat(h.header()).isEqualTo("Interakt-Signature");
            assertThat(h.algo()).isEqualTo("HmacSHA256");
            assertThat(h.enc()).isEqualTo(InboundVerification.Enc.HEX);
            assertThat(h.prefix()).isEqualTo("sha256=");
        }

        /**
         * <b>Runs the real verifier against INTERAKT'S OWN published worked example.</b>
         *
         * <p>This is the one test here that could actually falsify us: a wrong prefix, a wrong encoding
         * or an HMAC over the wrong bytes all fail here — and in production would fail CLOSED for every
         * honest delivery, 100% of the time, which is the exact trap the SPI's javadoc calls out.
         */
        @Test
        void verifiesInteraktsOwnPublishedSignatureExample() {
            String payload = "{\"foo\":1,\"bar\":2}";
            String signature = "sha256=b84783d10ede5bd6ed771e8b16fbe5a7093340159d6e49ec4248350b6ec2c7b4";

            RawInbound in = new HttpRawInbound(
                    payload.getBytes(StandardCharsets.UTF_8),
                    Map.of("Interakt-Signature", signature),
                    Map.of(),
                    new IntegrationCredentials(Map.of(InboundVerifier.SIGNING_SECRET_KEY, "examplekey")),
                    objectMapper);

            assertThat(new InboundVerifier().verify(adapter.verification(), in, "whatsapp")).isTrue();
        }

        /** A forged delivery must not pass — the whole point of signing. */
        @Test
        void rejectsAWrongSignature() {
            RawInbound in = new HttpRawInbound(
                    "{\"foo\":1,\"bar\":2}".getBytes(StandardCharsets.UTF_8),
                    Map.of("Interakt-Signature", "sha256=" + "0".repeat(64)),
                    Map.of(),
                    new IntegrationCredentials(Map.of(InboundVerifier.SIGNING_SECRET_KEY, "examplekey")),
                    objectMapper);

            assertThat(new InboundVerifier().verify(adapter.verification(), in, "whatsapp")).isFalse();
        }

        @Test
        void needsTheSigningSecretTheVerifierReads() {
            assertThat(adapter.catalog().requiredCredentialKeys())
                    .containsExactly(InboundVerifier.SIGNING_SECRET_KEY);
        }
    }
}
