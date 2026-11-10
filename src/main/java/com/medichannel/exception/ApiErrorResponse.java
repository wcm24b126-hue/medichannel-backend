
package com.medichannel.exception;

import java.time.Instant;

public record ApiErrorResponse(
        boolean success,
        String errorCode,
        String message,
        String referenceId,
        Instant timestamp
) {
}
