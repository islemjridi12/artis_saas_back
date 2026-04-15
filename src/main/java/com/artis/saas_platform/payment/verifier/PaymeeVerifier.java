package com.artis.saas_platform.payment.verifier;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymeeVerifier {

    @Value("${paymee.api-token}")
    private String apiToken;

    public boolean isValid(String token, boolean paymentStatus, String checkSum) {
        String expected = DigestUtils.md5Hex(token + (paymentStatus ? "1" : "0") + apiToken);
        return expected.equalsIgnoreCase(checkSum);
    }
}
