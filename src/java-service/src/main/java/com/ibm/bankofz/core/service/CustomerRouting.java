package com.ibm.bankofz.core.service;

/**
 * CICS vs IMS customer routing as done by src/frontend/js/utils.js
 * {@code parseCustomerId}: IDs prefixed 'C' are CICS/Db2 customers
 * (/customers/...), 'I' are IMS customers (/ims/customers/...). The digits
 * after the prefix are the backend customer number.
 */
public final class CustomerRouting {

    public enum Backend { CICS, IMS }

    public record RoutedId(Backend backend, String number) {
    }

    private CustomerRouting() {
    }

    public static RoutedId parse(String customerId) {
        if (customerId == null || customerId.length() < 2) {
            throw new IllegalArgumentException("customer ID must be a C/I prefix followed by a number");
        }
        char prefix = Character.toUpperCase(customerId.charAt(0));
        String rest = customerId.substring(1);
        if (!rest.matches("\\d+")) {
            throw new IllegalArgumentException("customer number must be numeric: " + customerId);
        }
        return switch (prefix) {
            case 'C' -> new RoutedId(Backend.CICS, rest);
            case 'I' -> new RoutedId(Backend.IMS, rest);
            default -> throw new IllegalArgumentException("unknown customer ID prefix: " + prefix);
        };
    }

    public static String format(Backend backend, String number) {
        return (backend == Backend.CICS ? "C" : "I") + number;
    }
}
