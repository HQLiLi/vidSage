package com.vidsage.application.exception;

public class InvalidWorkDirectoryException extends IllegalArgumentException {
    public InvalidWorkDirectoryException(String msg) {
        super(msg);
    }
}
