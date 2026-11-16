package com.example.Digital.Banking.System.Entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "transaction.idempotency")
public class IdempotencyProperties {

    private long retentionHours = 24;

    private long processingTimeoutMinutes = 5;

    private long waitTimeoutSeconds = 5;
}
