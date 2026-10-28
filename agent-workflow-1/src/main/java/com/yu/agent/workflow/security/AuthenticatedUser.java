package com.yu.agent.workflow.security;

import com.yu.agent.workflow.domain.UserRole;

import java.util.Set;

public record AuthenticatedUser(String userId, UserRole role, Set<String> roles) {
}
