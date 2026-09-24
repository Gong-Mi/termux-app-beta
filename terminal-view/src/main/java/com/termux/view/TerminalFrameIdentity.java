package com.termux.view;

import java.util.Objects;

/**
 * Immutable identity of a render request.
 *
 * <p>The model revision alone is not sufficient: a selection/cursor/viewport
 * projection can change without changing the model, and a view target can be
 * recreated while the session remains alive.</p>
 */
public final class TerminalFrameIdentity {
    public final long sessionGeneration;
    public final long targetGeneration;
    public final long modelRevision;
    public final long projectionRevision;

    public TerminalFrameIdentity(long sessionGeneration, long targetGeneration,
                                 long modelRevision, long projectionRevision) {
        this.sessionGeneration = sessionGeneration;
        this.targetGeneration = targetGeneration;
        this.modelRevision = modelRevision;
        this.projectionRevision = projectionRevision;
    }

    /** Whether this request belongs to the same attached session and render target. */
    public boolean isCompatibleWith(TerminalFrameIdentity other) {
        return other != null
            && sessionGeneration == other.sessionGeneration
            && targetGeneration == other.targetGeneration;
    }

    /**
     * Whether this request is strictly newer within the same session/target.
     * Both model and projection revisions must advance monotonically; this is
     * intentionally a partial order, not model-first lexicographic ordering.
     */
    public boolean isNewerThan(TerminalFrameIdentity other) {
        if (!isCompatibleWith(other)) return false;
        return modelRevision >= other.modelRevision
            && projectionRevision >= other.projectionRevision
            && (modelRevision > other.modelRevision
                || projectionRevision > other.projectionRevision);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof TerminalFrameIdentity)) return false;
        TerminalFrameIdentity other = (TerminalFrameIdentity) object;
        return sessionGeneration == other.sessionGeneration
            && targetGeneration == other.targetGeneration
            && modelRevision == other.modelRevision
            && projectionRevision == other.projectionRevision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionGeneration, targetGeneration, modelRevision, projectionRevision);
    }
}
