package vip.mate.cron.delivery;

import org.springframework.lang.Nullable;

/**
 * RFC-063r §2.6.0: outcome of a single cron-result delivery attempt.
 *
 * <p>Two states only — {@code DELIVERED} and {@code SKIPPED}. There is no
 * {@code FAILED} state; failures throw from
 * {@link AbstractCronResultDelivery#doDeliver}, get marked
 * {@code NOT_DELIVERED} on the run row by the template method, and surface
 * to the listener as exceptions for audit. Single-purpose return semantics.
 */
public record DeliveryOutcome(Status status,
                              @Nullable String target,
                              @Nullable String reason) {

    public enum Status { DELIVERED, SKIPPED }

    public static DeliveryOutcome delivered(String target) {
        return new DeliveryOutcome(Status.DELIVERED, target, null);
    }

    public static DeliveryOutcome skipped(String reason) {
        return new DeliveryOutcome(Status.SKIPPED, null, reason);
    }
}
