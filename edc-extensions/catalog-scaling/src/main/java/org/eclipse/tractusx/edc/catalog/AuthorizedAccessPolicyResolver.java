/*
 * Copyright (c) 2024 Cofinity-X
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

import java.util.Set;
import java.util.stream.Collectors;

public class AuthorizedAccessPolicyResolver {

    private final CatalogRequestQueryManager queryManager;
    private final PolicyEngine policyEngine;

    public AuthorizedAccessPolicyResolver(CatalogRequestQueryManager queryManager, PolicyEngine policyEngine) {
        this.queryManager = queryManager;
        this.policyEngine = policyEngine;
    }

    /**
     * Resolves authorized access policies referenced by contract definitions in the participant
     * context. Each referenced policy is loaded and evaluated once.
     *
     * @param participantContext participant context id
     * @param agent the requesting participant agent
     * @return authorized access policy IDs for the given participant
     */
    public Set<String> resolveFor(ParticipantContext participantContext, ParticipantAgent agent) {
        return queryManager.queryAccessPolicies(participantContext.getParticipantContextId()).stream()
                .filter(policy -> policyEngine.evaluate(policy.getPolicy(), new CatalogPolicyContext(agent)).succeeded())
                .map(PolicyDefinition::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
