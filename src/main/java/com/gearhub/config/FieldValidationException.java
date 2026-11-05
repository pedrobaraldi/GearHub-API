package com.gearhub.config;

import java.util.Map;

/** Validação que depende do banco, como os campos obrigatórios de cada categoria. */
public class FieldValidationException extends RuntimeException {

    private final transient Map<String, String> errors;

    public FieldValidationException(Map<String, String> errors) {
        super("Campos inválidos");
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
