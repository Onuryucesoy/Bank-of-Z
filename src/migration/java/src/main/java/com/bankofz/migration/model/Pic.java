package com.bankofz.migration.model;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a DTO record component to the copybook item it was derived from. The
 * conformance tests re-read the copybook and fail when {@link #value()},
 * {@link #usage()} or {@link #redefinedBy()} no longer match it, so the DTOs
 * cannot silently drift from the canonical COBOL definitions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Pic {

    /** Copybook path relative to the repository root. */
    String copybook();

    /** COBOL data name, e.g. {@code ACCOUNT-AVAILABLE-BALANCE}. */
    String field();

    /**
     * PIC clause of the item. For a group item (e.g. the {@code DD/MM/YYYY} date
     * groups in CUSTOMER.cpy) this is the equivalent elementary picture of the
     * whole group, e.g. {@code 9(8)}.
     */
    String value();

    Usage usage() default Usage.DISPLAY;

    /** Name of the group that {@code REDEFINES} this item, if any. */
    String redefinedBy() default "";
}
