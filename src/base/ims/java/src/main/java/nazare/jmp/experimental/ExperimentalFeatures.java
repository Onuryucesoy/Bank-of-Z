package nazare.jmp.experimental;

/**
 * Opt-in switches for experimental code. Everything is off unless explicitly enabled.
 *
 * <p>{@value #IMS_READ_ONLY_PROPERTY} (system property) or {@value #IMS_READ_ONLY_ENV}
 * (environment variable) set to {@code true} enables the read-only IMS reader.
 */
public final class ExperimentalFeatures {

    public static final String IMS_READ_ONLY_PROPERTY = "nazare.experimental.ims.readonly";
    public static final String IMS_READ_ONLY_ENV = "NAZARE_EXPERIMENTAL_IMS_READONLY";

    private ExperimentalFeatures() {
    }

    public static boolean imsReadOnlyEnabled() {
        String value = System.getProperty(IMS_READ_ONLY_PROPERTY);
        if (value == null) {
            value = System.getenv(IMS_READ_ONLY_ENV);
        }
        return Boolean.parseBoolean(value);
    }

    public static void requireImsReadOnly() {
        if (!imsReadOnlyEnabled()) {
            throw new IllegalStateException("Experimental IMS read-only access is disabled; set -D"
                    + IMS_READ_ONLY_PROPERTY + "=true to enable it outside production");
        }
    }
}
