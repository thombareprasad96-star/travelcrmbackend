// tenant/exception/TenantNotFoundException.java
package com.crm.travelcrm.tenent.exception;

import java.util.UUID;

public class TenantNotFoundException extends RuntimeException {
    public TenantNotFoundException(long id) {
        super("Tenant not found with id: " + id);
    }
}