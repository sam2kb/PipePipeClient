package org.schabi.newpipe.restricted

/**
 * Thrown (or emitted as an Rx error) when Restricted Mode refuses an operation.
 */
class RestrictedModeException(message: String) : RuntimeException(message)
