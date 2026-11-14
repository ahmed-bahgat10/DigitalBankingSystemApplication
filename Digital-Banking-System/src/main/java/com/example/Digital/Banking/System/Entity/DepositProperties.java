package com.example.Digital.Banking.System.Entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "transaction.deposit")
@Getter
@Setter
public class DepositProperties {
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
}
