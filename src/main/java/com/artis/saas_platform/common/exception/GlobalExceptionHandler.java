package com.artis.saas_platform.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {

        if (ex.getMessage().contains("Tenant domain already used")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("message", "Tenant déjà utilisé")
            );
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                Map.of("message", ex.getMessage())
        );
    }
}
