/*
 * Copyright (c) 2026 Volkswagen AG
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.tractusx.edc.catalog;

import org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext;
import org.eclipse.edc.connector.controlplane.policy.spi.PolicyDefinition;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizedAccessPolicyResolverTest {

    private static final String PARTICIPANT_CONTEXT_ID = "participant-context";

    private final CatalogRequestQueryManager queryManager = mock();
    private final PolicyEngine policyEngine = mock();
    private final AuthorizedAccessPolicyResolver resolver = new AuthorizedAccessPolicyResolver(queryManager, policyEngine);
    private final ParticipantContext participantContext = ParticipantContext.Builder.newInstance()
            .participantContextId(PARTICIPANT_CONTEXT_ID)
            .identity("identity")
            .build();
    private final ParticipantAgent agent = new ParticipantAgent("agent", Map.of(), Map.of());

    @Test
    void resolveFor_shouldReturnIdsOfPoliciesPassingEvaluation() {
        var allowed = policyDefinition("allowed-policy");
        var denied = policyDefinition("denied-policy");
        when(queryManager.queryAccessPolicies(PARTICIPANT_CONTEXT_ID)).thenReturn(List.of(allowed, denied));
        when(policyEngine.evaluate(eq(allowed.getPolicy()), any(CatalogPolicyContext.class))).thenReturn(Result.success());
        when(policyEngine.evaluate(eq(denied.getPolicy()), any(CatalogPolicyContext.class))).thenReturn(Result.failure("denied"));

        assertThat(resolver.resolveFor(participantContext, agent)).containsExactly("allowed-policy");

        var context = ArgumentCaptor.forClass(CatalogPolicyContext.class);
        verify(policyEngine).evaluate(eq(allowed.getPolicy()), context.capture());
        assertThat(context.getValue().participantAgent()).isSameAs(agent);
        verify(policyEngine).evaluate(eq(denied.getPolicy()), any(CatalogPolicyContext.class));
    }

    @Test
    void resolveFor_shouldReturnEmpty_whenNoPoliciesAreReferenced() {
        when(queryManager.queryAccessPolicies(PARTICIPANT_CONTEXT_ID)).thenReturn(List.of());

        assertThat(resolver.resolveFor(participantContext, agent)).isEmpty();
        verify(policyEngine, never()).evaluate(any(), any());
    }

    private PolicyDefinition policyDefinition(String id) {
        return PolicyDefinition.Builder.newInstance()
                .id(id)
                .policy(Policy.Builder.newInstance().target(id).build())
                .build();
    }
}
