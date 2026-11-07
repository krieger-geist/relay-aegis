package com.relayaegis.runtime;

import java.util.Optional;

/** Read-only runtime state and logs of one recovery target, whatever it runs on. */
public interface RuntimeInspector {
    boolean canInspect(String target);

    /** Empty when inspection is not permitted or did not complete; a "missing" snapshot when the target does not exist. */
    Optional<ContainerSnapshot> inspect(String target);

    LogExcerpt logs(String target);
}
