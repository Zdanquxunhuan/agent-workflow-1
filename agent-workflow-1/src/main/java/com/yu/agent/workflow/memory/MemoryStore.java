package com.yu.agent.workflow.memory;

import com.yu.agent.workflow.api.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Repository
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public class MemoryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final RowMapper<MemoryRecord> ROW = (r,n) -> new MemoryRecord(r.getString("id"),r.getString("tenant_id"),
            r.getString("owner_id"),r.getString("kind"),r.getString("slot"),r.getString("content"),r.getString("source_task_id"),
            r.getLong("revision"),r.getString("state"),r.getLong("expires_at"),r.getLong("created_at"),r.getLong("updated_at"));
    public MemoryStore(JdbcTemplate jdbc, PlatformTransactionManager workflowTransactionManager) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(workflowTransactionManager);
    }
    public <T> T transaction(Supplier<T> work) { return tx.execute(status -> work.get()); }
    public static long now() { return Instant.now().getEpochSecond(); }
    // Invoke repository methods through the Spring proxy; never read fields on its CGLIB shell.
    public long activeCount(String tenant,String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM long_term_memories WHERE tenant_id=? AND owner_id=? AND state IN ('ACTIVE','CANDIDATE') AND expires_at>?",Long.class,tenant,owner,now());
    }
    public boolean automaticExtraction(String tenant,String owner) {
        return !jdbc.queryForList("SELECT owner_id FROM memory_owners WHERE tenant_id=? AND owner_id=? AND auto_extract=true",tenant,owner).isEmpty();
    }
    public void automaticExtraction(String tenant,String owner,boolean enabled) {
        jdbc.update("UPDATE memory_owners SET auto_extract=? WHERE tenant_id=? AND owner_id=?",enabled,tenant,owner);
    }
    public boolean jobExists(String id) {return !jdbc.queryForList("SELECT id FROM memory_jobs WHERE id=?",id).isEmpty();}
    public long recentExtractions(String tenant,String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE tenant_id=? AND owner_id=? AND kind='EXTRACT' AND created_at>?",Long.class,tenant,owner,now()-86400);
    }
    public void lockSource(String task) {jdbc.queryForList("SELECT id FROM tasks WHERE id=? FOR SHARE",task);}
    public void sourceReauthorized(MemoryRecord memory) {
        jdbc.update("INSERT INTO memory_events VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),memory.id(),memory.revision(),"SOURCE_REAUTHORIZED",now());
    }
    public void lockOwner(String tenant,String owner) {
        jdbc.update("INSERT IGNORE INTO memory_owners(tenant_id,owner_id) VALUES (?,?)",tenant,owner);
        jdbc.queryForList("SELECT owner_id FROM memory_owners WHERE tenant_id=? AND owner_id=? FOR UPDATE",tenant,owner);
    }
    public MemoryRecord find(String id) {
        var rows=jdbc.query("SELECT * FROM long_term_memories WHERE id=?",ROW,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public MemoryRecord owned(String id,String tenant,String owner) {
        var m=find(id);
        if(m==null || !m.tenantId().equals(tenant) || !m.ownerId().equals(owner))
            throw BusinessException.of("NOT_FOUND","Memory does not exist");
        return m;
    }
    public List<MemoryRecord> list(String tenant,String owner) {
        return page(tenant,owner,"");
    }
    public List<MemoryRecord> page(String tenant,String owner,String after) {
        return jdbc.query("SELECT * FROM long_term_memories WHERE tenant_id=? AND owner_id=? AND id>? ORDER BY id LIMIT 100",ROW,tenant,owner,after);
    }
    public List<MemoryRecord> preferences(String tenant,String owner) {
        return jdbc.query("SELECT * FROM long_term_memories WHERE tenant_id=? AND owner_id=? AND kind='PREFERENCE' AND state='ACTIVE' AND expires_at>? ORDER BY slot LIMIT 3",ROW,tenant,owner,now());
    }
    public boolean hasSummaries(String tenant,String owner) {
        return !jdbc.queryForList("SELECT id FROM long_term_memories WHERE tenant_id=? AND owner_id=? AND kind='TASK_SUMMARY' AND state='ACTIVE' AND expires_at>? LIMIT 1",tenant,owner,now()).isEmpty();
    }
    public MemoryRecord slot(String tenant,String owner,String kind,String slot) {
        var rows=jdbc.query("SELECT * FROM long_term_memories WHERE tenant_id=? AND owner_id=? AND kind=? AND slot=?",ROW,tenant,owner,kind,slot);
        return rows.isEmpty()?null:rows.get(0);
    }
    public String replay(String tenant,String owner,String key,String hash) {
        jdbc.update("DELETE FROM memory_requests WHERE tenant_id=? AND owner_id=? AND request_key=? AND expires_at<=?",tenant,owner,key,now());
        var rows=jdbc.queryForList("SELECT * FROM memory_requests WHERE tenant_id=? AND owner_id=? AND request_key=?",tenant,owner,key);
        if(rows.isEmpty()) return null;
        if(!hash.equals(rows.get(0).get("request_hash"))) throw BusinessException.of("IDEMPOTENCY_CONFLICT","Key has different memory input");
        return rows.get(0).get("memory_id").toString();
    }
    public void request(String tenant,String owner,String key,String hash,String id) {
        jdbc.update("INSERT INTO memory_requests VALUES (?,?,?,?,?,?)",tenant,owner,key,hash,id,now()+7*86400L);
    }
    public void insert(MemoryRecord m) {
        jdbc.update("INSERT INTO long_term_memories VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",m.id(),m.tenantId(),m.ownerId(),m.kind(),m.slot(),
                m.content(),m.sourceTaskId(),m.revision(),m.state(),m.expiresAt(),m.createdAt(),m.updatedAt());
        changed(m,"CANDIDATE_CREATED");
    }
    public void update(MemoryRecord old,MemoryRecord next,String event) {
        if(jdbc.update("UPDATE long_term_memories SET content=?,revision=?,state=?,expires_at=?,updated_at=? WHERE id=? AND revision=?",
                next.content(),next.revision(),next.state(),next.expiresAt(),next.updatedAt(),old.id(),old.revision())!=1)
            throw BusinessException.of("CONFLICT","Memory revision changed; reload");
        changed(next,event);
    }
    private void changed(MemoryRecord m,String event) {
        jdbc.update("INSERT INTO memory_events VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),m.id(),m.revision(),event,now());
        if("TASK_SUMMARY".equals(m.kind())) enqueue("index-"+m.id(),m.tenantId(),m.ownerId(),"INDEX",m.id(),m.revision());
    }
    public void enqueue(String id,String tenant,String owner,String kind,String target,long revision) {
        jdbc.update("INSERT INTO memory_jobs(id,tenant_id,owner_id,kind,target_id,revision,available_at,created_at) VALUES (?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE revision=VALUES(revision),state='PENDING',attempts=0,lease_token=NULL,lease_until=0,available_at=VALUES(available_at),error_code=NULL",
                id,tenant,owner,kind,target,revision,now(),now());
    }
    public record Job(String id,String tenant,String owner,String kind,String target,long revision,String token,int attempts) {}
    public Job claim() {
        return transaction(() -> {
            var rows=jdbc.queryForList("SELECT * FROM memory_jobs WHERE ((state='PENDING' AND available_at<=?) OR (state='RUNNING' AND lease_until<=?)) AND attempts<5 ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED",now(),now());
            if(rows.isEmpty()) return null;
            var r=rows.get(0); String token=UUID.randomUUID().toString();
            jdbc.update("UPDATE memory_jobs SET state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?",token,now()+90,r.get("id"));
            return new Job(r.get("id").toString(),r.get("tenant_id").toString(),r.get("owner_id").toString(),r.get("kind").toString(),
                    r.get("target_id").toString(),((Number)r.get("revision")).longValue(),token,((Number)r.get("attempts")).intValue()+1);
        });
    }
    public void finish(Job j,boolean success,String errorCode) {
        jdbc.update("UPDATE memory_jobs SET state=?,available_at=?,error_code=?,lease_token=NULL,lease_until=0 WHERE id=? AND lease_token=? AND revision=?",
                success?"DONE":j.attempts()>=5?"DEAD":"PENDING",now()+Math.min(300,5L*(1L<<j.attempts())),success?null:errorCode,j.id(),j.token(),j.revision());
    }
    public List<Map<String,Object>> jobs(String tenant,String owner) {
        return jdbc.queryForList("SELECT id,kind,target_id,revision,state,attempts,error_code FROM memory_jobs WHERE tenant_id=? AND owner_id=? ORDER BY created_at DESC LIMIT 100",tenant,owner);
    }
    public void retry(String id,String tenant,String owner) {
        if(jdbc.update("UPDATE memory_jobs SET state='PENDING',attempts=0,available_at=?,error_code=NULL WHERE id=? AND tenant_id=? AND owner_id=? AND state='DEAD'",now(),id,tenant,owner)!=1)
            throw BusinessException.of("CONFLICT","No failed job available to retry");
    }
    public void reapAbandoned() {
        jdbc.update("DELETE FROM memory_requests WHERE expires_at<=? LIMIT 1000",now());
        jdbc.update("UPDATE memory_jobs SET state='DEAD',error_code='LEASE_EXHAUSTED',lease_token=NULL WHERE state='RUNNING' AND attempts>=5 AND lease_until<=?",now());
    }
    public List<MemoryRecord> expired() {
        return jdbc.query("SELECT * FROM long_term_memories WHERE state IN ('ACTIVE','CANDIDATE') AND expires_at<=? LIMIT 50",ROW,now());
    }
    public void bind(String task,List<MemoryRecord> memories,List<String> parents) {
        transaction(() -> {
            for(var m:memories) jdbc.update("INSERT IGNORE INTO task_memory_uses VALUES (?,?,?)",task,m.id(),m.revision());
            for(String p:parents) if(!task.equals(p)) jdbc.update("INSERT IGNORE INTO task_memory_parents VALUES (?,?)",task,p);
            return null;
        });
    }
    public List<Map<String,Object>> uses(String task) { return jdbc.queryForList("SELECT memory_id,revision FROM task_memory_uses WHERE task_id=?",task); }
    public List<String> parents(String task) { return jdbc.queryForList("SELECT source_task_id FROM task_memory_parents WHERE task_id=?",String.class,task); }
    public List<Map<String,Object>> events(String id) {
        return jdbc.queryForList("SELECT revision,event_type,occurred_at FROM memory_events WHERE memory_id=? ORDER BY occurred_at,revision LIMIT 200",id);
    }
}
