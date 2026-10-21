package com.relayaegis.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * Password policy: 10+ characters, at most 72 UTF-8 bytes (BCrypt ignores anything longer), at least one letter and
 * at least one digit or symbol, and not only whitespace at either end. Mirrored in the frontend strength meter.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {
    String message() default "must be at least 10 characters and include a letter and a number or symbol";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    int MIN_LENGTH = 10;
    int MAX_BYTES = 72;

    class Validator implements ConstraintValidator<StrongPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) return true; // @NotBlank reports missing values.
            String problem = problem(value);
            if (problem == null) return true;
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
            return false;
        }

        static String problem(String value) {
            if (value.length() < MIN_LENGTH) return "must be at least " + MIN_LENGTH + " characters";
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "must be at most " + MAX_BYTES + " bytes";
            if (!value.equals(value.strip())) return "must not start or end with whitespace";
            boolean letter = value.codePoints().anyMatch(Character::isLetter);
            boolean other = value.codePoints().anyMatch(c -> Character.isDigit(c) || (!Character.isLetter(c) && !Character.isWhitespace(c)));
            if (!letter || !other) return "must include a letter and a number or symbol";
            return null;
        }
    }
}
