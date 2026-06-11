package com.bank.bian.savingsaccount.domain;

/** Business-rule failures, classified so the API layer can map status codes. */
public class DomainException extends RuntimeException {

    public enum Kind { NOT_FOUND, RULE_VIOLATION, INVALID }

    private final Kind kind;
    private final String code;

    public DomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind getKind() { return kind; }
    public String getCode() { return code; }

    public static DomainException notFound(String code, String msg) {
        return new DomainException(Kind.NOT_FOUND, code, msg);
    }
    public static DomainException rule(String code, String msg) {
        return new DomainException(Kind.RULE_VIOLATION, code, msg);
    }
    public static DomainException invalid(String code, String msg) {
        return new DomainException(Kind.INVALID, code, msg);
    }
}
