package com.yu.agent.workflow;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.TaskStateMachine;
import com.yu.agent.workflow.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTests {
    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(TaskStateMachine.allowedFrom(TaskStatus.SUCCEEDED)).isEmpty();
        assertThat(TaskStateMachine.allowedFrom(TaskStatus.FAILED)).isEmpty();
        assertThat(TaskStateMachine.allowedFrom(TaskStatus.CANCELLED)).isEmpty();
    }

    @Test
    void illegalTransitionIsRejected() {
        assertThatThrownBy(() -> TaskStateMachine.requireAllowed(TaskStatus.SUCCEEDED, TaskStatus.RUNNING))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Illegal transition");
    }

    @Test
    void recoveryStateOnlyReturnsToExecutionThroughExplicitResume() {
        TaskStateMachine.requireAllowed(TaskStatus.RUNNING, TaskStatus.RECOVERY_REQUIRED);
        TaskStateMachine.requireAllowed(TaskStatus.RECOVERY_REQUIRED, TaskStatus.RUNNING);
        assertThat(TaskStateMachine.allowedFrom(TaskStatus.RECOVERY_REQUIRED))
                .contains(TaskStatus.RUNNING, TaskStatus.CANCELLED);
    }
}
