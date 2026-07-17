package com.crm.travelcrm.leadsource.adapter;

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
 * The Google Ads lead-form adapter's contract.
 *
 * <p><b>Unlike {@link JustDialAdapterTest}, these fixtures are not our own invention.</b> The payload in
 * {@link OfficialSample} is Google's published sample, copied verbatim from their webhook documentation.
 * That is what makes these tests evidence rather than a restatement of an assumption — the difference
 * between a provider that documents its contract and one that does not.
 */
class GoogleAdsLeadFormAdapterTest {

    private final GoogleAdsLeadFormAdapter adapter = new GoogleAdsLeadFormAdapter();
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

    /** Google's published sample, verbatim — except is_test, which they document separately. */
    private static final String GOOGLE_SAMPLE = """
            {
              "lead_id":"Cj0KCQjwit_8BRCoARIsAIx3Rj7g-AeL6z35IWb6VYiZUygtTfwD3hDlgSGmY",
              "campaign_id":123456,
              "adgroup_id":0,
              "creative_id":0,
              "gcl_id":"Cj0KCQjwit_8BRCoARIsAIx3Rj7g-AeL6z35IWb6VYiZUygtTfwD3hDlgSGmY",
              "user_column_data": [
                {"column_name":"Full Name","string_value":"FirstName LastName","column_id":"FULL_NAME"},
                {"column_name":"User Phone","string_value":"1-650-555-0123","column_id":"PHONE_NUMBER"},
                {"column_name":"User Email","string_value":"test@example.com","column_id":"EMAIL"}
              ],
              "api_version":"1.0",
              "form_id":123456789,
              "google_key":"testkey",
              "is_test":false
            }""";

    @Nested
    @DisplayName("Google's own published sample payload")
    class OfficialSample {

        @Test
        void parsesTheContactFields() {
            NormalizedLead lead = onlyLead(adapter.parse(body(GOOGLE_SAMPLE)));

            assertThat(lead.customerName()).isEqualTo("FirstName LastName");
            assertThat(lead.phoneRaw()).isEqualTo("1-650-555-0123");
            assertThat(lead.email()).isEqualTo("test@example.com");
        }

        /** gcl_id is the click id — the thing that makes cost-per-lead computable rather than guessed. */
        @Test
        void carriesTheClickIdAndCampaignForAttribution() {
            NormalizedLead lead = onlyLead(adapter.parse(body(GOOGLE_SAMPLE)));

            assertThat(lead.attribution()).isNotNull();
            assertThat(lead.attribution().gclid()).startsWith("Cj0KCQjwit_8BRCoARIsAIx3Rj7g");
            assertThat(lead.attribution().campaignId()).isEqualTo("123456");
            assertThat(lead.attribution().formId()).isEqualTo("123456789");
        }

        /** Google's sample sends adgroup_id:0 / creative_id:0 — zero means absent, not id zero. */
        @Test
        void treatsZeroIdsAsAbsentRatherThanAsIdZero() {
            NormalizedLead lead = onlyLead(adapter.parse(body(GOOGLE_SAMPLE)));

            assertThat(lead.attribution().adSetId()).isNull();
            assertThat(lead.attribution().adId()).isNull();
        }

        /** Google retries on 5xx and does not guarantee exactly-once. lead_id is the only defence. */
        @Test
        void dedupesOnLeadId() {
            assertThat(adapter.dedupKey(body(GOOGLE_SAMPLE)))
                    .isEqualTo(IdempotencyKey.of(
                            "Cj0KCQjwit_8BRCoARIsAIx3Rj7g-AeL6z35IWb6VYiZUygtTfwD3hDlgSGmY"));
        }

        /** A plain contact form carries no trip — it must not drag an empty travel record around. */
        @Test
        void hasNoTravelIntentWhenTheFormAskedNothingAboutTheTrip() {
            assertThat(onlyLead(adapter.parse(body(GOOGLE_SAMPLE))).travel()).isNull();
        }
    }

    @Nested
    @DisplayName("a travel lead form — the reason this channel converts")
    class TravelForm {

        private static final String TRAVEL_LEAD = """
                {
                  "lead_id":"abc123",
                  "campaign_id":555,
                  "user_column_data": [
                    {"column_name":"Full Name","string_value":"Asha Rao","column_id":"FULL_NAME"},
                    {"column_name":"User Phone","string_value":"9876543210","column_id":"PHONE_NUMBER"},
                    {"column_name":"Destination city","string_value":"Kathmandu","column_id":"DESTINATION_CITY"},
                    {"column_name":"Departure city","string_value":"Gorakhpur","column_id":"DEPARTURE_CITY"},
                    {"column_name":"Departure country","string_value":"India","column_id":"DEPARTURE_COUNTRY"},
                    {"column_name":"Departure date","string_value":"2026-08-15","column_id":"DEPARTURE_DATE"},
                    {"column_name":"Number of travelers","string_value":"4","column_id":"NUMBER_OF_TRAVELERS"},
                    {"column_name":"Travel budget","string_value":"80000","column_id":"TRAVEL_BUDGET"},
                    {"column_name":"Which month are you planning?","string_value":"August","column_id":"CUSTOM_QUESTION_1"}
                  ],
                  "google_key":"testkey"
                }""";

        @Test
        void carriesTheTripVerbatim() {
            NormalizedLead.TravelIntent travel = onlyLead(adapter.parse(body(TRAVEL_LEAD))).travel();

            assertThat(travel).isNotNull();
            assertThat(travel.departureCity()).isEqualTo("Gorakhpur");
            assertThat(travel.departureCountry()).isEqualTo("India");
            assertThat(travel.destinationCity()).isEqualTo("Kathmandu");
            assertThat(travel.departureDate()).isEqualTo("2026-08-15");
            assertThat(travel.travellers()).isEqualTo("4");
            assertThat(travel.budget()).isEqualTo("80000");
        }

        /**
         * The adapter must NOT parse. It cannot know whether this advertiser's form offers a free number
         * or one of Google's dropdown buckets, and a guess here writes a silently wrong budget onto a
         * real lead. LeadIngestService converts only what is unambiguous.
         */
        @Test
        void passesValuesThroughUnparsed() {
            NormalizedLead.TravelIntent travel = onlyLead(adapter.parse(body(TRAVEL_LEAD))).travel();

            assertThat(travel.budget()).isInstanceOf(String.class).isEqualTo("80000");
            assertThat(travel.departureDate()).isInstanceOf(String.class);
        }

        /**
         * <b>A custom question is usually the most valuable answer on the form</b>, and its column_id is
         * by definition unknown to us — so the message is built from column_name, which for a custom
         * question IS the question text. Dropping unknown ids would silently bin exactly this.
         */
        @Test
        void keepsACustomQuestionByItsQuestionText() {
            String message = onlyLead(adapter.parse(body(TRAVEL_LEAD))).message();

            assertThat(message).contains("Which month are you planning?: August");
        }

        /** Everything the lead's own fields do not carry must still reach the desk. */
        @Test
        void putsTheTripInTheMessageToo() {
            String message = onlyLead(adapter.parse(body(TRAVEL_LEAD))).message();

            assertThat(message).contains("Kathmandu").contains("Gorakhpur").contains("80000");
        }

        /** Contact fields are consumed into real lead fields — repeating them as notes is noise. */
        @Test
        void doesNotRepeatContactFieldsInTheMessage() {
            String message = onlyLead(adapter.parse(body(TRAVEL_LEAD))).message();

            assertThat(message).doesNotContain("Asha Rao").doesNotContain("9876543210");
        }
    }

    @Nested
    @DisplayName("traffic that must not become a lead")
    class NonLeadTraffic {

        /**
         * Google's "Send test data" button. Creating a real lead from it would put fiction in the
         * pipeline AND burn the tenant's plan quota — while the delivery is still logged, so the test
         * button remains a genuine end-to-end proof of the wiring.
         */
        @Test
        void aTestLeadIsIgnoredRatherThanCreated() {
            InboundParseResult result = adapter.parse(body("""
                    {"lead_id":"x","is_test":true,"google_key":"testkey",
                     "user_column_data":[{"column_id":"EMAIL","string_value":"t@e.com"}]}"""));

            assertThat(result).isInstanceOf(InboundParseResult.Ignored.class);
            assertThat(((InboundParseResult.Ignored) result).reason()).isEqualTo("test_lead");
        }

        /** "If value is false or if field is not present, treat this lead as valid production lead." */
        @Test
        void anAbsentIsTestMeansAProductionLead() {
            assertThat(adapter.parse(body("""
                    {"lead_id":"x","user_column_data":[{"column_id":"EMAIL","string_value":"t@e.com"}]}""")))
                    .isInstanceOf(InboundParseResult.Complete.class);
        }

        @Test
        void aFormWithNoContactDetailsIsIgnored() {
            assertThat(adapter.parse(body("""
                    {"lead_id":"x","user_column_data":[{"column_id":"CITY","string_value":"Pune"}]}""")))
                    .isInstanceOf(InboundParseResult.Ignored.class);
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
        void servesTheGoogleAdsChannel() {
            assertThat(adapter.channel()).isEqualTo(LeadSourceChannel.GOOGLE_ADS);
        }

        /** google_key is the advertiser's key, echoed by Google — exactly SharedSecretInBody. */
        @Test
        void verifiesTheGoogleKeyFromTheBody() {
            assertThat(adapter.verification())
                    .isInstanceOf(InboundVerification.SharedSecretInBody.class);
            assertThat(((InboundVerification.SharedSecretInBody) adapter.verification()).jsonPath())
                    .isEqualTo("google_key");
        }

        /**
         * <b>Coupled to the test above, and the coupling is the point.</b> google_key is a LIVE
         * credential in the body; unredacted it would sit in raw_payload next to PII for the whole
         * retention window.
         */
        @Test
        void redactsTheGoogleKeyFromTheStoredPayload() {
            assertThat(adapter.secretFieldPaths()).containsExactly("google_key");
        }

        /** The credential-bag key is a framework contract — InboundVerifier reads exactly this name. */
        @Test
        void storesTheKeyUnderTheNameTheVerifierReads() {
            assertThat(adapter.catalog().requiredCredentialKeys())
                    .containsExactly(InboundVerifier.SHARED_SECRET_KEY);
        }

        /**
         * Google Ads is global, so the country is genuinely unknown — unlike JustDial, an India-only
         * marketplace. Hinting "+91" here would mangle an overseas enquiry.
         */
        @Test
        void hintsNoCountryBecauseGoogleAdsIsGlobal() {
            assertThat(onlyLead(adapter.parse(body(GOOGLE_SAMPLE))).phoneCountryHint()).isNull();
        }

        /** Google: "Don't write code that expects a fixed set of fields." */
        @Test
        void toleratesUnknownTopLevelFieldsGoogleMayAddLater() {
            assertThat(adapter.parse(body("""
                    {"lead_id":"x","some_future_field":{"nested":true},
                     "user_column_data":[{"column_id":"EMAIL","string_value":"t@e.com"}]}""")))
                    .isInstanceOf(InboundParseResult.Complete.class);
        }

        @Test
        void buildsTheNameFromPartsWhenFullNameIsAbsent() {
            NormalizedLead lead = onlyLead(adapter.parse(body("""
                    {"lead_id":"x","user_column_data":[
                      {"column_id":"FIRST_NAME","string_value":"Asha"},
                      {"column_id":"LAST_NAME","string_value":"Rao"},
                      {"column_id":"PHONE_NUMBER","string_value":"9876543210"}]}""")));

            assertThat(lead.customerName()).isEqualTo("Asha Rao");
        }
    }
}
