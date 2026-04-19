package vip.mate.wiki.job.event;

/**
 * RFC-031: Published when a new wiki processing job is created,
 * triggering the dispatcher to execute it.
 */
public record WikiJobCreatedEvent(Long jobId) {}
