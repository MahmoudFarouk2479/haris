package dev.haris.tools;

/** How much a single action can hurt, which decides who must approve it. */
public enum Risk {
    /** Harmless or read-only: runs without asking (still audited). */
    SAFE,
    /** Changes something but can be undone: the user approves it, or approves the tool for the session. */
    CONFIRM,
    /** Destructive, irreversible or leaves the machine: the user approves every single time. */
    CRITICAL
}
