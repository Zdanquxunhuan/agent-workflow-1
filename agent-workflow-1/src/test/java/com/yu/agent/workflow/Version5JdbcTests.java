package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.application.RagTaskAccess;
import com.yu.agent.workflow.domain.*;
import com.yu.agent.workflow.persistence.JdbcTaskRepository;
import com.yu.agent.workflow.tools.ToolContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="V5_MYSQL_URL",matches=".+")
class Version5JdbcTests {
    @Test void migrationPreservesLegacyAndPersistsTenantScopeAndRevocationAcrossRestart() {
        String url=System.getenv("V5_MYSQL_URL");
        // A dedicated throwaway database is required, never a working schema.
        assertThat(url).contains("/v5_test");
        var ds=new DriverManagerDataSource(url,"root","v5_isolated_test_only");
        var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("6").load().migrate();
        jdbc.update("INSERT INTO tasks(id,user_id,role,request,status,created_at,updated_at) VALUES ('old','alice','CUSTOMER_SERVICE','old','SUCCEEDED',NOW(),NOW())");
        Flyway.configure().dataSource(ds).load().migrate();
        var json=new ObjectMapper().findAndRegisterModules();
        var tasks=new JdbcTaskRepository(jdbc,json);
        assertThat(tasks.require("old").tenantId()).isEqualTo("legacy");
        var a=new AgentTask("a","alice",UserRole.CUSTOMER_SERVICE,"query","key",Instant.now(),"demo","chat");
        tasks.create(a);
        tasks.create(new AgentTask("b","alice",UserRole.CUSTOMER_SERVICE,"query","key",Instant.now(),"other","chat"));
        assertThat(tasks.create(new AgentTask("retry","alice",UserRole.CUSTOMER_SERVICE,"query","key",Instant.now(),"demo","chat")).id()).isEqualTo("a");
        a.resultMessage("answer"); tasks.save(a);
        assertThat(tasks.require("a").view().resultMessage()).isEqualTo("answer");
        assertThat(tasks.require("b").tenantId()).isEqualTo("other");
        var beans=new DefaultListableBeanFactory(); beans.registerSingleton("jdbc",jdbc);
        var access=new RagTaskAccess(tasks,json,beans.getBeanProvider(JdbcTemplate.class));
        var context=new ToolContext("alice",UserRole.CUSTOMER_SERVICE,"a","trace","demo");
        access.scope(context,List.of("kb1"));
        access.remember("a",List.of(new RagTaskAccess.Source("chunk","version","hash",1)));
        var restored=new RagTaskAccess(new JdbcTaskRepository(jdbc,json),json,beans.getBeanProvider(JdbcTemplate.class));
        assertThat(restored.grant("a").kbIds()).containsExactly("kb1");
        assertThat(restored.sources("a")).hasSize(1);
        try {
            Version5Tests.authenticate("demo","alice"); restored.revoke("a","alice");
            assertThat(access.grant("a").revoked()).isTrue();
            ReflectionTestUtils.setField(access,"enabled",true);
            assertThatThrownBy(() -> access.token(context)).isInstanceOf(com.yu.agent.workflow.api.BusinessException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
