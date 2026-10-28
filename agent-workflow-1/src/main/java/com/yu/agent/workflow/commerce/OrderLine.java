package com.yu.agent.workflow.commerce;

import java.math.BigDecimal;

public record OrderLine(String sku, String title, int quantity, BigDecimal unitPrice) {
}
