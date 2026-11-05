package com.gearhub.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Exige ao menos uma letra maiúscula, um número e um caractere especial. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {

    String message() default "Use ao menos uma letra maiúscula, um número e um caractere especial";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrongPassword, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // Senha vazia já é tratada por @NotBlank
            if (value == null || value.isBlank()) {
                return true;
            }
            boolean upper = value.codePoints().anyMatch(Character::isUpperCase);
            boolean digit = value.codePoints().anyMatch(Character::isDigit);
            boolean special = value.codePoints()
                    .anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
            return upper && digit && special;
        }
    }
}
