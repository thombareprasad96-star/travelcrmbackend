package com.crm.travelcrm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonicalisation contract for {@code leads.phone_normalized}.
 *
 * <p>These tests are the reason the column can be trusted as a dedup key. The failure mode being
 * defended against is silent: a wrong canonical form does not throw, it just quietly matches the
 * wrong person or nobody at all.
 */
class PhoneCanonicalizerTest {

    private static final String IN = "+91";

    @Nested
    @DisplayName("the formats real rows and real webhooks actually carry")
    class RealWorldFormats {

        /**
         * The whole point of the column. DevDataSeeder writes "+91 91234 50001" (spaces); inbound
         * channels deliver "+919123450001". Dedup compares raw, so these never match today and the
         * repeat caller silently becomes a duplicate lead. Every form below MUST collapse to one key.
         */
        @ParameterizedTest(name = "{0} -> +919123450001")
        @ValueSource(strings = {
                "+919123450001",        // bare E.164, as inbound delivers
                "+91 91234 50001",      // DevDataSeeder's format — spaces
                "+91-91234-50001",      // dashes
                "  +919123450001  ",    // padded
                "+91 (91234) 50001",    // brackets
                "919123450001",         // country code present, no '+' — marketplace webhooks
                "9123450001",           // national
                "09123450001",          // national with trunk zero — IVR sends these
                "0 9123 450001",        // trunk zero + separators
        })
        void allCollapseToTheSameKey(String raw) {
            assertThat(PhoneCanonicalizer.canonical(raw, IN)).isEqualTo("+919123450001");
        }
    }

    @Nested
    @DisplayName("the '+'-less country code, which is where a naive prepend goes wrong")
    class CountryCodeWithoutPlus {

        @Test
        void countryCodeAlreadyPresentIsNotPrependedTwice() {
            // A naive implementation returns "+91919812345678" here: wrong, matches nothing, and no
            // test containing a '+' would ever catch it.
            assertThat(PhoneCanonicalizer.canonical("919812345678", IN)).isEqualTo("+919812345678");
        }

        @Test
        void nationalNumberStartingWithCountryCodeDigitsIsStillNational() {
            // "9198765432" is a 10-digit national number that happens to start with "91". It must NOT
            // be read as a country code — that is the trap the length check exists for.
            assertThat(PhoneCanonicalizer.canonical("9198765432", IN)).isEqualTo("+919198765432");
        }
    }

    @Nested
    @DisplayName("refuses to guess — null beats a wrong answer")
    class RefusesToGuess {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "abc", "n/a", "-", "()"})
        void noDigitsYieldsNull(String raw) {
            assertThat(PhoneCanonicalizer.canonical(raw, IN)).isNull();
        }

        @Test
        void nullInNullOut() {
            assertThat(PhoneCanonicalizer.canonical(null, IN)).isNull();
        }

        @Test
        void zerosOnlyYieldsNull() {
            assertThat(PhoneCanonicalizer.canonical("000", IN)).isNull();
        }

        @ParameterizedTest(name = "national-format \"{0}\" with no hint -> null")
        @ValueSource(strings = {"9123450001", "09123450001"})
        void nationalFormatWithoutACountryHintYieldsNull(String raw) {
            // A wrong country code is a wrong person. Refusing is the only safe answer.
            assertThat(PhoneCanonicalizer.canonical(raw, null)).isNull();
            assertThat(PhoneCanonicalizer.canonical(raw, "  ")).isNull();
        }

        @ParameterizedTest(name = "\"{0}\" is neither national nor cc-prefixed -> null")
        @ValueSource(strings = {
                "12345",              // short code
                "123456789",          // 9 digits — one short, so not a national number
                "12345678901",        // 11 digits — not 10, not 12-with-91
                "441632960961",       // a UK number typed without its '+': prepending +91 is nonsense
        })
        void unrecognisableLengthsYieldNull(String raw) {
            assertThat(PhoneCanonicalizer.canonical(raw, IN)).isNull();
        }

        @Test
        void anExplicitPlusIsAlwaysTrustedRegardlessOfLength() {
            // With a '+' the caller has told us it is international; no guessing is involved, so the
            // length rules above must not reject it.
            assertThat(PhoneCanonicalizer.canonical("+441632960961", IN)).isEqualTo("+441632960961");
            assertThat(PhoneCanonicalizer.canonical("+1 555 010 9999", IN)).isEqualTo("+15550109999");
        }
    }

    @Nested
    @DisplayName("idempotence — the backfill and the write path must agree forever")
    class Idempotence {

        /**
         * The backfill reuses this exact method. If canonical(canonical(x)) != canonical(x), a second
         * backfill run would rewrite keys and silently repartition lead identity.
         */
        @ParameterizedTest
        @CsvSource({
                "+91 91234 50001",
                "9123450001",
                "09123450001",
                "919123450001",
                "+15550109999",
        })
        void canonicalisingTwiceChangesNothing(String raw) {
            String once = PhoneCanonicalizer.canonical(raw, IN);
            assertThat(PhoneCanonicalizer.canonical(once, IN)).isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("the hint is parsed leniently, since '+91' and '91' both appear in config")
    class CountryHintParsing {

        @ParameterizedTest
        @ValueSource(strings = {"+91", "91", " +91 "})
        void hintFormatDoesNotChangeTheResult(String hint) {
            assertThat(PhoneCanonicalizer.canonical("9123450001", hint)).isEqualTo("+919123450001");
        }
    }
}
