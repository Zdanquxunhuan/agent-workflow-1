package com.yu.agent.workflow.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcPersistenceConfiguration {
    @Bean
    DataSource workflowDataSource(
            @Value("${POSTGRES_URL:jdbc:postgresql://localhost:5432/workflow_agent}") String url,
            @Value("${POSTGRES_USER:workflow}") String user,
            @Value("${POSTGRES_PASSWORD:workflow_dev_only}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        return dataSource;
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
