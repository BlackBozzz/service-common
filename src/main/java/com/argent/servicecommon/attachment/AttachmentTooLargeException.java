package com.argent.servicecommon.attachment;

/** Поток файла длиннее допустимого размера; запись прервана, временный файл удалён. */
public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException(long maxBytes) {
        super("Файл превышает допустимый размер: больше " + maxBytes + " байт");
    }
}
