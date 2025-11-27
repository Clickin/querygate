package generic.db.gateway.validation;

import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when parameter validation fails.
 * Contains a list of all validation errors encountered.
 */
public class ValidationException extends RuntimeException {

    private final List<String> errors;

    /**
     * Creates a validation exception with a single error message.
     */
    public ValidationException(String message) {
        super(message);
        this.errors = Collections.singletonList(message);
    }

    /**
     * Creates a validation exception with multiple error messages.
     */
    public ValidationException(String message, List<String> errors) {
        super(message + ": " + String.join(", ", errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * Returns the list of validation errors.
     */
    public List<String> getErrors() {
        return errors;
    }

    /**
     * Checks if there are multiple validation errors.
     */
    public boolean hasMultipleErrors() {
        return errors.size() > 1;
    }
}
