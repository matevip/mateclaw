package vip.mate.workflow.runtime;

import vip.mate.workflow.compiler.ir.WorkflowStep;

/**
 * Strategy interface for executing a single workflow step. One implementation
 * per {@code StepMode.typeName()}; the runner looks up the adapter by name and
 * calls {@link #execute}. Adapters MUST NOT mutate {@link WorkflowRunContext}
 * directly — the runner publishes the {@link StepResult} into the context so
 * fan_out groups can merge in deterministic order.
 */
public interface StepAdapter {

    /**
     * The mode name this adapter handles — must match
     * {@code StepMode.typeName()} (sequential / fan_out / collect / conditional /
     * await_approval / dispatch_channel / write_memory).
     */
    String typeName();

    /**
     * Execute one step. Implementations should never throw to signal a normal
     * step failure — return {@link StepResult#failed(String)} instead. Throwing
     * is reserved for programmer / framework errors that should abort the run.
     */
    StepResult execute(WorkflowStep step, WorkflowRunContext context);
}
