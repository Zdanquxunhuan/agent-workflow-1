package com.yu.agent.workflow;

import com.yu.agent.workflow.commerce.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class CommerceConfirmationTests {
    CommerceOutbox outbox=mock(CommerceOutbox.class);
    CommerceGateway gateway=mock(CommerceGateway.class);
    CommerceCommandDispatcher dispatcher=new CommerceCommandDispatcher(outbox,gateway,3,30,1,6);
    CommerceCommand command(String state,int attempt) { return new CommerceCommand("cmd","BUSINESS_HTTP","REFUND","ref","{}","key",attempt,state); }

    @Test void acceptedRefundWaitsForLedgerConfirmation() {
        var c=command("PENDING",1);
        when(gateway.supportsStatusQuery()).thenReturn(true);
        when(gateway.query(c)).thenReturn(new CommerceGateway.GatewayResult(null,null,"NOT_FOUND",""));
        when(gateway.execute(c)).thenReturn(new CommerceGateway.GatewayResult("external",null,"PENDING",""));
        dispatcher.process(c,"owner",false);
        verify(outbox).awaitConfirmation(c,"owner","external");
        verify(outbox,never()).completeRefund(any(),anyString(),any());
    }
    @Test void timeoutRecoveryQueriesExistingLedgerEntryWithoutResubmitting() {
        var c=command("UNKNOWN",2);
        when(gateway.supportsStatusQuery()).thenReturn(true);
        when(gateway.query(c)).thenReturn(new CommerceGateway.GatewayResult("external",null,"SUCCEEDED",""));
        dispatcher.process(c,"owner",false);
        verify(gateway,never()).execute(any());
        verify(outbox).completeRefund(c,"owner","external");
    }
    @Test void uncertainMissingReceiptGoesToHumansWithoutBlindReplay() {
        var c=command("UNKNOWN",2);
        when(gateway.supportsStatusQuery()).thenReturn(true);
        when(gateway.query(c)).thenReturn(new CommerceGateway.GatewayResult(null,null,"NOT_FOUND",""));
        dispatcher.process(c,"owner",false);
        verify(gateway,never()).execute(any());
        verify(outbox).markManualReview(eq(c),eq("owner"),contains("investigation"));
    }
    @Test void manualRecheckCannotIssueANewWrite() {
        var c=command("MANUAL_REVIEW",7);
        when(gateway.supportsStatusQuery()).thenReturn(true);
        when(gateway.query(c)).thenReturn(new CommerceGateway.GatewayResult(null,null,"NOT_FOUND",""));
        dispatcher.process(c,"manual",true);
        verify(gateway,never()).execute(any());
        verify(outbox).markManualReview(eq(c),eq("manual"),anyString());
    }
    @Test void pendingConfirmationHasABoundedBudget() {
        var c=command("AWAITING_CONFIRMATION",6);
        when(gateway.supportsStatusQuery()).thenReturn(true);
        when(gateway.query(c)).thenReturn(new CommerceGateway.GatewayResult("external",null,"PENDING",""));
        dispatcher.process(c,"owner",false);
        verify(outbox).markManualReview(eq(c),eq("owner"),contains("budget"));
    }
    @Test void commandsAreLeasedOneAtATime() {
        var c=command("PENDING",1);
        when(outbox.claim(anyString(),eq(1),any())).thenReturn(List.of(c),List.of());
        when(gateway.execute(c)).thenReturn(new CommerceGateway.GatewayResult("ref",null));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        verify(outbox,never()).claim(anyString(),eq(3),any());
    }
}
