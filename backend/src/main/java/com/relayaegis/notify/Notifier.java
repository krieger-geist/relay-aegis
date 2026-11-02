package com.relayaegis.notify;

/** One outgoing channel. {@link #send} runs on the dispatcher's thread and must not throw. */
public interface Notifier {
    String name();

    boolean enabled();

    void send(RelayNotification notification);
}
