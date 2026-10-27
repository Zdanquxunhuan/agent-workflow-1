package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.TaskPlan;

public interface TaskPlanner {
    TaskPlan plan(String request);
}
