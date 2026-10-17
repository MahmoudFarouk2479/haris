package dev.haris.agent;

/** The user pressed Stop: the reply ends and this exchange is forgotten. */
public class AgentStoppedException extends RuntimeException {

    public AgentStoppedException() {
        super("Stopped.");
    }
}
