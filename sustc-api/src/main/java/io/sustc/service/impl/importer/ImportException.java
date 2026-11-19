package io.sustc.service.impl.importer;

/** Thrown when the bulk import fails; the database is left empty with its full schema. */
public class ImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }

    public ImportException(String message) {
        super(message);
    }
}
