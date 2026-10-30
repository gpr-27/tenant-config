package com.moveinsync.config.server.push;

public class TooManyConnectionsException extends RuntimeException {

    public TooManyConnectionsException() {
        super("the server is at its push connection limit; retry shortly");
    }
}
