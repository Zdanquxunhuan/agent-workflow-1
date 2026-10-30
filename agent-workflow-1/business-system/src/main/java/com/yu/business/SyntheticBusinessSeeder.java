package com.yu.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

/** Repeatable, insert-only fixture creation. Never overwrites existing ledger state. */
@Component
public class SyntheticBusinessSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public SyntheticBusinessSeeder(JdbcTemplate jdbc, @Value("${business.seed:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        for (int i = 1; i <= 50; i++) {
            String id = "ORD-" + (3000 + i);
            BigDecimal amount = BigDecimal.valueOf(12000L + i * 731L, 2);
            String behavior = switch (i) { case 2 -> "TIMEOUT_AFTER_ACCEPT"; case 3 -> "DECLINE"; case 4 -> "HOLD"; default -> "NORMAL"; };
            boolean paid = i != 5;
            Timestamp now = Timestamp.from(Instant.now().minusSeconds(i * 3600L));
            jdbc.update("""
                    insert ignore into orders(id,customer_id,customer_name,status,total_amount,paid_amount,shipping_address,refund_behavior,updated_at)
                    values (?,?,?,?,?,?,?,?,?)
                    """, id, "SYN-CUSTOMER-" + i, "合成客户" + i, paid ? "PAID" : "CREATED", amount,
                    paid ? amount : BigDecimal.ZERO, "合成市测试区演示路" + i + "号（非真实地址）", behavior, now);
            jdbc.update("insert ignore into order_items values (?,?,?,?,?)", id, "SYN-SKU-" + i, "合成商品" + i, 1, amount);
            if (paid) jdbc.update("""
                    insert ignore into payments(id,order_id,status,amount,gateway_txn_id,paid_at)
                    values (?,?,'SUCCEEDED',?,?,?)
                    """, "PAY-" + (3000 + i), id, amount, "LOCAL-LEDGER-" + i, now);
        }
    }
}
