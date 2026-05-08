package vip.mate.workflow.runtime;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry mapping mode {@code typeName} to its {@link StepAdapter} bean.
 * Spring autowires every adapter on the classpath; the runner asks the
 * registry which adapter to use and the registry rejects unknown modes
 * up-front so a wiring bug surfaces at the run boundary instead of inside
 * the executor loop.
 */
@Component
public class StepAdapterRegistry {

    private final Map<String, StepAdapter> adapters;

    public StepAdapterRegistry(List<StepAdapter> adapters) {
        Map<String, StepAdapter> mapped = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(StepAdapter::typeName, Function.identity()));
        this.adapters = mapped;
    }

    public StepAdapter get(String typeName) {
        StepAdapter adapter = adapters.get(typeName);
        if (adapter == null) {
            throw new IllegalStateException("no step adapter registered for mode: " + typeName);
        }
        return adapter;
    }

    public boolean has(String typeName) {
        return adapters.containsKey(typeName);
    }
}
