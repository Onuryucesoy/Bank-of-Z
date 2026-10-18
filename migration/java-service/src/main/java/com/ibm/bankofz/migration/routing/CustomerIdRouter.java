package com.ibm.bankofz.migration.routing;

import com.ibm.bankofz.migration.api.ZosAsset.Backend;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Server-side twin of {@code validateCustomerId} / {@code parseCustomerId} in {@code src/frontend/js/utils.js}.
 * Routing invariant: {@code C<digits>} goes to CICS/Db2, {@code I<exactly 9 digits>} goes to IMS. The shadow
 * proxy (Phase 2+) must route with this class so that Java and z/OS Connect always agree on the backend.
 */
public final class CustomerIdRouter {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int IMS_DIGITS = 9;

    private CustomerIdRouter() {
    }

    public record Route(Backend backend, String customerId) {
    }

    public record Validation(boolean valid, String error, Backend backend) {
        static Validation invalid(String error) {
            return new Validation(false, error, null);
        }
    }

    /** Mirrors {@code validateCustomerId}, including its error messages. */
    public static Validation validate(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Validation.invalid("Customer ID is required");
        }
        String trimmed = input.trim().toUpperCase(Locale.ROOT);
        if (!trimmed.startsWith("C") && !trimmed.startsWith("I")) {
            return Validation.invalid("Customer ID must start with C (CICS) or I (IMS)");
        }
        char prefix = trimmed.charAt(0);
        String digits = trimmed.substring(1);
        if (!DIGITS.matcher(digits).matches()) {
            return Validation.invalid("Customer ID must contain only digits after the prefix");
        }
        if (prefix == 'I' && digits.length() != IMS_DIGITS) {
            return Validation.invalid("IMS customer ID must have exactly 9 digits after I prefix (e.g., I000000015)");
        }
        return new Validation(true, null, prefix == 'I' ? Backend.IMS : Backend.CICS);
    }

    /** Mirrors {@code parseCustomerId}: returns the backend and the digits that are sent to it. */
    public static Optional<Route> route(String input) {
        Validation v = validate(input);
        if (!v.valid()) {
            return Optional.empty();
        }
        return Optional.of(new Route(v.backend(), input.trim().substring(1)));
    }
}
