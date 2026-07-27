package com.crm.travelcrm.auth.mfa;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService service = new TotpService();

    @Test
    void codeAtMatchesRfc6238SecretWithSixDigits() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // "12345678901234567890"

        assertThat(service.codeAt(secret, 59 / 30)).isEqualTo("287082");
        assertThat(service.codeAt(secret, 1_111_111_109L / 30)).isEqualTo("081804");
    }

    @Test
    void verifyAcceptsCurrentWindowCode() {
        String secret = service.generateSecret();
        String code = service.codeAt(secret, Instant.now().getEpochSecond() / 30);

        assertThat(service.verify(secret, code)).isTrue();
        assertThat(service.verify(secret, "000000".equals(code) ? "000001" : "000000")).isFalse();
    }
}
