/**
 * Copyright 2011, Stanford University. This file is licensed under GPL v2 plus
 * a special exception, as described in included LICENSE_EXCEPTION.txt.
 */
package net.beaconcontroller.core.internal;

public enum SwitchState {
    HELLO_SENT,
    FEATURES_REQUEST_SENT,
    GET_CONFIG_REQUEST_SENT,
    INITIALIZING,
    ACTIVE
}
