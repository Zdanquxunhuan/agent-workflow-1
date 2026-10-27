package com.yu.agent.workflow.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The service uses transactional approval methods so the JDBC adapter can
 * hold a row lock across the approval decision and execution. The memory
 * profile has no DataSource, therefore it needs a no-op manager for the same
 * service proxy to remain callable in local tests and offline demos.
 */
@Configuration
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTransactionConfiguration {
    @Bean
    PlatformTransactionManager workflowTransactionManager() {
        return new NoOpTransactionManager();
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // No resource is bound in the memory profile.
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // No resource is bound in the memory profile.
        }
    }

}
