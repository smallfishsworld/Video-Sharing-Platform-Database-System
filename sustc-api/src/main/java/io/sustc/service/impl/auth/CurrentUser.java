package io.sustc.service.impl.auth;

/**
 * An authenticated user.
 *
 * @param mid the user's id
 * @param superuser whether the user's identity is {@code SUPERUSER}
 */
public record CurrentUser(long mid, boolean superuser) {}
