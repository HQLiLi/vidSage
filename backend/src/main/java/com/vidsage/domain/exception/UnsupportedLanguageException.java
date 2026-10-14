package com.vidsage.domain.exception;

/**
 * 不支持语种异常
 */
public class UnsupportedLanguageException extends RuntimeException {
    public UnsupportedLanguageException(String input) {
        super("当前输入语种无法匹配支持语种，输入：" + input);
    }
}
