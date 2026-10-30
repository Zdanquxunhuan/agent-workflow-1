package com.yu.agent.workflow.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** Loads the optional local demo dataset; disabled by default. */
@Component
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class CommerceDemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CommerceDemoDataSeeder.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public CommerceDemoDataSeeder(JdbcTemplate jdbc,
                                  @Value("${agent.commerce.seed-demo-data:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/seed/commerce-demo-data.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(jdbc.getDataSource());
        Integer orders = jdbc.queryForObject("select count(*) from orders", Integer.class);
        log.warn("Loaded demo commerce data (orders={}). Never enable COMMERCE_SEED_DEMO_DATA in production.", orders);
    }
}
