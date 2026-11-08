package dev.endfold.service;

/** Typed business failures. Each maps to a configurable message {@code errors.<lower_case_name>}. */
public enum Failure {
    NOT_FOUND,
    SPACE_NOT_ACTIVE,
    NOT_MEMBER,
    NO_PERMISSION,
    ALREADY_MEMBER,
    ALREADY_INVITED,
    ALREADY_REQUESTED,
    INVITE_EXPIRED,
    INVITE_NOT_FOR_YOU,
    SPACE_FULL,
    OWNED_LIMIT_REACHED,
    MEMBERSHIP_LIMIT_REACHED,
    SOLO_ALREADY_EXISTS,
    TYPE_DISABLED,
    INVALID_NAME,
    INVALID_VALUE,
    NOT_PUBLIC,
    APPROVAL_REQUIRED,
    OPEN_JOIN_ONLY,
    SOLO_NOT_SHAREABLE,
    CANNOT_TARGET_SELF,
    CANNOT_TARGET_OWNER,
    OWNER_MUST_TRANSFER,
    OWNER_MUST_DISBAND,
    ALREADY_CLAIMED,
    CLAIM_LIMIT_REACHED,
    CLAIM_COOLDOWN,
    PUBLIC_DISABLED,
    RATE_LIMITED,
    INTERNAL_ERROR;

    public String messageKey() { return "errors." + name().toLowerCase(java.util.Locale.ROOT); }
}
