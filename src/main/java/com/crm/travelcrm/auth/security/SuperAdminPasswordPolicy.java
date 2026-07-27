package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminPasswordPolicy {

    @Value("${app.super-admin.password.min-length:12}")
    private int minLength;

    @Value("${app.super-admin.password.max-length:128}")
    private int maxLength;

    public void validate(String password) {
        if (password == null) {
            reject();
        }

        String value = password == null ? "" : password;
        if (value.length() < minLength || value.length() > maxLength) {
            reject();
        }

        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                reject();
            }
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else symbol = true;
        }

        if (!lower || !upper || !digit || !symbol) {
            reject();
        }
    }

    private void reject() {
        throw new BusinessException(
                "SuperAdmin password must be 12-128 characters and include uppercase, lowercase, number, and symbol.",
                HttpStatus.BAD_REQUEST);
    }
}
