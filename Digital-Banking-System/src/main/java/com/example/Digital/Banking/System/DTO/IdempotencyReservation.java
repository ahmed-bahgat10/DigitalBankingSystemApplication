package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Entity.IdempotencyRecord;

public record IdempotencyReservation(
        IdempotencyRecord record,
        boolean newlyCreated
) {
}
