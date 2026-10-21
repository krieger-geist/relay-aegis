package com.relayaegis.remediation;

/** A patch could not be proposed; the message is safe to show to the operator. */
public class RemediationException extends Exception {
    public RemediationException(String message) { super(message); }
}
