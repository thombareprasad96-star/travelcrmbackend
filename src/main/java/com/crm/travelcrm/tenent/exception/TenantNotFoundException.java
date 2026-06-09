// tenant/exception/TenantNotFoundException.java
package com.crm.travelcrm.tenent.exception;

public class TenantNotFoundException extends RuntimeException {
    public TenantNotFoundException(Long id) {
        super("Tenant not found with id: " + id);
    }
}