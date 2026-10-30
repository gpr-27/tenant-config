package com.moveinsync.config.server.rollout;

// names the office and key only: the values involved may be ones an operator would not want echoed
public class StagedChangeSupersededException extends RuntimeException {

    public StagedChangeSupersededException(String office, String key) {
        super("office " + office + " no longer holds the staged value for " + key
                + "; it was changed, removed or already promoted since staging");
    }
}
