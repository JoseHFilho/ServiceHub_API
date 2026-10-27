package com.servicehub.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PlainText.Validator.class)
public @interface PlainText {
    String message() default "Informe texto simples, sem marcação ou caracteres de controle.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    class Validator implements ConstraintValidator<PlainText, String> {
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.chars().noneMatch(c -> c == '<' || c == '>'
                    || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'));
        }
    }
}
