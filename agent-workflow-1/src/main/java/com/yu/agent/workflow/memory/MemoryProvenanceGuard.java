package com.yu.agent.workflow.memory;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.KnowledgeSearchClient;
import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Always active for JDBC, even when memory creation/recall is disabled later. */
@Component
@ConditionalOnProperty(name="agent.workflow.persistence",havingValue="jdbc")
public class MemoryProvenanceGuard {
    private final JdbcTemplate jdbc;private final KnowledgeSearchClient knowledge;
    public MemoryProvenanceGuard(JdbcTemplate jdbc,KnowledgeSearchClient knowledge){this.jdbc=jdbc;this.knowledge=knowledge;}
    @Transactional(transactionManager="workflowTransactionManager")
    public void inherit(String task,List<String> parents){
        for(String parent:new LinkedHashSet<>(parents))if(!task.equals(parent))
            jdbc.update("INSERT IGNORE INTO task_memory_parents VALUES (?,?)",task,parent);
    }
    public void validateTask(ToolContext context){
        Set<String> visited=new HashSet<>();Deque<String> pending=new ArrayDeque<>();pending.add(context.taskId());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(!pending.isEmpty()){
            if(System.nanoTime()>deadline)throw BusinessException.of("MODEL_DEADLINE","Memory provenance validation timed out");
            String id=pending.removeFirst();if(!visited.add(id))continue;
            if(visited.size()>200)throw BusinessException.of("MODEL_CONTEXT_BUDGET","Memory provenance graph exceeds limit");
            var uses=jdbc.queryForList("SELECT u.revision AS used_revision,m.* FROM task_memory_uses u LEFT JOIN long_term_memories m ON m.id=u.memory_id WHERE u.task_id=?",id);
            for(var m:uses){
                if(m.get("id")==null || !context.tenantId().equals(m.get("tenant_id")) || !context.userId().equals(m.get("owner_id"))
                        || !"ACTIVE".equals(m.get("state")) || ((Number)m.get("expires_at")).longValue()<=MemoryStore.now()
                        || ((Number)m.get("revision")).longValue()!=((Number)m.get("used_revision")).longValue())
                    throw BusinessException.of("MEMORY_CHANGED","Private memory is unavailable or has changed");
                if(m.get("source_task_id")!=null)pending.add(m.get("source_task_id").toString());
            }
            // Root caller already checks its knowledge; ancestors must be checked here as well.
            if(!id.equals(context.taskId()))knowledge.revalidateTask(new ToolContext(context.userId(),context.role(),id,context.traceId(),context.tenantId()));
            pending.addAll(jdbc.queryForList("SELECT source_task_id FROM task_memory_parents WHERE task_id=?",String.class,id));
        }
        if(System.nanoTime()>deadline)throw BusinessException.of("MODEL_DEADLINE","Memory provenance validation timed out");
    }
}
