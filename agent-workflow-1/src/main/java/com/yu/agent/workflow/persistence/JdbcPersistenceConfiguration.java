package com.yu.agent.workflow.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcPersistenceConfiguration {
    @Bean(destroyMethod = "close")
    DataSource workflowDataSource(
            @Value("${mysql.url:jdbc:mysql://localhost:3307/workflow_agent}") String url,
            @Value("${mysql.user:workflow}") String user,
            @Value("${mysql.password:workflow_dev_only}") String password,
            @Value("${mysql.pool.max-size:10}") int maxPoolSize,
            @Value("${mysql.pool.min-idle:2}") int minIdle,
            @Value("${mysql.pool.connection-timeout-ms:5000}") long connectionTimeoutMs) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("workflow-mysql");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(1, maxPoolSize));
        config.setMinimumIdle(Math.max(0, Math.min(minIdle, maxPoolSize)));
        config.setConnectionTimeout(Math.max(250, connectionTimeoutMs));
        config.setValidationTimeout(Math.max(250, Math.min(connectionTimeoutMs, 5000)));
        config.setConnectionTestQuery("select 1");
        // Fail closed: an unreachable business database must stop startup instead
        // of silently falling back to in-memory data.
        config.setInitializationFailTimeout(1);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate workflowJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager workflowTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
