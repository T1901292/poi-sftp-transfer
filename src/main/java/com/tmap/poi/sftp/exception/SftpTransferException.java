package com.tmap.poi.sftp.exception;

import lombok.Getter;

@Getter
public class SftpTransferException extends RuntimeException {
    private final String path;
    private final boolean isLocal;

    public SftpTransferException(String msg, String path, boolean isLocal, Throwable cause) {
        super(msg, cause);
        this.path = path;
        this.isLocal = isLocal;
    }
}