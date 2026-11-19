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

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.catalog.spi.Dataset;
import org.eclipse.edc.connector.controlplane.catalog.spi.DistributionResolver;
import org.eclipse.edc.connector.controlplane.contract.spi.ContractOfferId;
import org.eclipse.edc.connector.controlplane.policy.spi.PolicyDefinition;
import org.eclipse.edc.connector.controlplane.policy.spi.store.PolicyDefinitionStore;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.policy.model.PolicyType;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.query.SortOrder;
import org.eclipse.tractusx.edc.catalog.AuthorizedAsset.MatchingContractDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.participantcontext.spi.types.ParticipantResource.filterByParticipantContextId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ScalingDatasetResolverTest {

    private static final String PARTICIPANT_CONTEXT_ID = "participant-context";
    private static final String PROTOCOL = "protocol";
    private static final String ASSET_ID = "asset";
    private static final String DEFINITION_ID_1 = "definition";
    private static final String DEFINITION_ID_2 = "other-definition";
    private static final String CONTRACT_POLICY_ID = "contract-policy";
    private static final String MISSING_CONTRACT_POLICY_ID = "missing-contract-policy";
    private static final String ACCESS_POLICY_ID = "access-policy";

    private final AuthorizedAccessPolicyResolver contractDefinitionResolver = mock();
    private final CatalogRequestQueryManager queryManager = mock();
    private final PolicyDefinitionStore policyDefinitionStore = mock();
    private final DistributionResolver distributionResolver = mock();

    private final ParticipantContext participantContext = ParticipantContext.Builder.newInstance()
            .participantContextId(PARTICIPANT_CONTEXT_ID)
            .identity("identity")
            .build();
    private final ParticipantAgent agent = new ParticipantAgent("agent", Map.of(), Map.of());

    private ScalingDatasetResolver resolver;

    @BeforeEach
    void setUp() {
        when(distributionResolver.getDistributions(eq(PROTOCOL), any())).thenReturn(List.of());
        when(policyDefinitionStore.findById(CONTRACT_POLICY_ID))
                .thenReturn(PolicyDefinition.Builder.newInstance().id(CONTRACT_POLICY_ID).policy(Policy.Builder.newInstance().build()).build());

        resolver = new ScalingDatasetResolver(contractDefinitionResolver, queryManager, policyDefinitionStore,
                distributionResolver);
    }

    @Test
    void query_shouldQueryAssetsWithAuthorizedAccessPolicies() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.queryAssetsWithMatchingContractDefinitions(anyString(), any(), any()))
                .thenReturn(List.of(new AuthorizedAsset(asset(), List.of(definition(DEFINITION_ID_1)))));
        var querySpec = QuerySpec.Builder.newInstance()
                .offset(5)
                .limit(10)
                .filter(new Criterion("key", "=", "value"))
                .build();

        var datasets = resolver.query(participantContext, agent, querySpec, PROTOCOL).toList();

        assertThat(datasets).singleElement().satisfies(dataset -> {
            assertThat(dataset.getId()).isEqualTo(ASSET_ID);
            assertThat(offeredDefinitionIds(dataset)).containsExactly(DEFINITION_ID_1);
        });

        var assetsQuery = ArgumentCaptor.forClass(QuerySpec.class);
        verify(queryManager).queryAssetsWithMatchingContractDefinitions(eq(PARTICIPANT_CONTEXT_ID), eq(authorizedPolicies()),
                assetsQuery.capture());
        verifyNoMoreInteractions(queryManager);

        assertThat(assetsQuery.getValue()).satisfies(query -> {
            assertThat(query.getOffset()).isEqualTo(5);
            assertThat(query.getLimit()).isEqualTo(10);
            assertThat(query.getFilterExpression()).containsExactly(new Criterion("key", "=", "value"),
                    filterByParticipantContextId(PARTICIPANT_CONTEXT_ID));
            assertThat(query.getSortField()).isEqualTo("id");
            assertThat(query.getSortOrder()).isEqualTo(SortOrder.ASC);
        });
    }

    @Test
    void query_shouldReturnEmptyStream_whenNoAssetIsFound() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.queryAssetsWithMatchingContractDefinitions(anyString(), any(), any()))
                .thenReturn(List.of());

        var datasets = resolver.query(participantContext, agent, QuerySpec.max(), PROTOCOL);

        assertThat(datasets).isEmpty();

        verify(queryManager).queryAssetsWithMatchingContractDefinitions(eq(PARTICIPANT_CONTEXT_ID), eq(authorizedPolicies()), any());
        verifyNoMoreInteractions(queryManager);
    }

    @Test
    void query_shouldNotQueryDatabase_whenNoContractDefinitionIsAuthorized() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(Set.of());

        var datasets = resolver.query(participantContext, agent, QuerySpec.max(), PROTOCOL);

        assertThat(datasets).isEmpty();
        verifyNoInteractions(queryManager);
    }

    @Test
    void getById_shouldFindAssetWithAuthorizedAccessPolicies() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.findAssetWithMatchingContractDefinitions(anyString(), any(), anyString()))
                .thenReturn(Optional.of(new AuthorizedAsset(asset(), List.of(definition(DEFINITION_ID_1), definition(DEFINITION_ID_2)))));

        var dataset = resolver.getById(participantContext, agent, ASSET_ID, PROTOCOL);

        assertThat(dataset).isNotNull();
        assertThat(dataset.getId()).isEqualTo(ASSET_ID);
        assertThat(offeredDefinitionIds(dataset)).containsExactlyInAnyOrder(DEFINITION_ID_1, DEFINITION_ID_2);

        verify(queryManager).findAssetWithMatchingContractDefinitions(PARTICIPANT_CONTEXT_ID, authorizedPolicies(), ASSET_ID);
        verifyNoMoreInteractions(queryManager);
        // both definitions share the contract policy, which is therefore loaded once
        verify(policyDefinitionStore, times(1)).findById(CONTRACT_POLICY_ID);
    }

    @Test
    void getById_shouldReturnNull_whenAssetIsNotFound() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.findAssetWithMatchingContractDefinitions(anyString(), any(), anyString())).thenReturn(Optional.empty());

        var dataset = resolver.getById(participantContext, agent, ASSET_ID, PROTOCOL);

        assertThat(dataset).isNull();

        verify(queryManager).findAssetWithMatchingContractDefinitions(PARTICIPANT_CONTEXT_ID, authorizedPolicies(), ASSET_ID);
        verifyNoMoreInteractions(queryManager);
    }

    @Test
    void getById_shouldNotQueryDatabase_whenNoContractDefinitionIsAuthorized() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(Set.of());

        var dataset = resolver.getById(participantContext, agent, ASSET_ID, PROTOCOL);

        assertThat(dataset).isNull();
        verifyNoInteractions(queryManager);
    }

    @Test
    void getById_shouldOfferContractPolicyReturnedByQuery() {
        var contractPolicy = Policy.Builder.newInstance().target("contract-policy-target").build();
        when(policyDefinitionStore.findById(CONTRACT_POLICY_ID))
                .thenReturn(PolicyDefinition.Builder.newInstance().id(CONTRACT_POLICY_ID).policy(contractPolicy).build());
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.findAssetWithMatchingContractDefinitions(anyString(), any(), anyString()))
                .thenReturn(Optional.of(new AuthorizedAsset(asset(), List.of(definition(DEFINITION_ID_1)))));

        var dataset = resolver.getById(participantContext, agent, ASSET_ID, PROTOCOL);

        assertThat(dataset).isNotNull();
        assertThat(dataset.getOffers().entrySet()).singleElement().satisfies(offer -> {
            assertThat(ContractOfferId.parseId(offer.getKey()).getContent().definitionPart()).isEqualTo(DEFINITION_ID_1);
            assertThat(offer.getValue().getTarget()).isEqualTo("contract-policy-target");
            assertThat(offer.getValue().getType()).isEqualTo(PolicyType.OFFER);
        });
    }

    @Test
    void getById_shouldSkipDefinition_whenContractPolicyDoesNotExist() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.findAssetWithMatchingContractDefinitions(anyString(), any(), anyString()))
                .thenReturn(Optional.of(new AuthorizedAsset(asset(), List.of(
                        definition(DEFINITION_ID_1),
                        new MatchingContractDefinition(DEFINITION_ID_2, MISSING_CONTRACT_POLICY_ID)))));

        var dataset = resolver.getById(participantContext, agent, ASSET_ID, PROTOCOL);

        assertThat(dataset).isNotNull();
        assertThat(offeredDefinitionIds(dataset)).containsExactly(DEFINITION_ID_1);
        verify(policyDefinitionStore).findById(MISSING_CONTRACT_POLICY_ID);
    }

    @Test
    void getById_shouldReturnNull_whenNoContractPolicyExists() {
        when(contractDefinitionResolver.resolveFor(participantContext, agent)).thenReturn(authorizedPolicies());
        when(queryManager.findAssetWithMatchingContractDefinitions(anyString(), any(), anyString()))
                .thenReturn(Optional.of(new AuthorizedAsset(asset(), List.of(
                        new MatchingContractDefinition(DEFINITION_ID_1, MISSING_CONTRACT_POLICY_ID)))));

        assertThat(resolver.getById(participantContext, agent, ASSET_ID, PROTOCOL)).isNull();
        verify(policyDefinitionStore, never()).findById(CONTRACT_POLICY_ID);
    }

    private Set<String> authorizedPolicies() {
        return Set.of(ACCESS_POLICY_ID);
    }

    private MatchingContractDefinition definition(String id) {
        return new MatchingContractDefinition(id, CONTRACT_POLICY_ID);
    }

    private Asset asset() {
        return Asset.Builder.newInstance()
                .id(ASSET_ID)
                .participantContextId(PARTICIPANT_CONTEXT_ID)
                .build();
    }

    private List<String> offeredDefinitionIds(Dataset dataset) {
        return dataset.getOffers().keySet().stream()
                .map(offerId -> ContractOfferId.parseId(offerId).getContent())
                .peek(offerId -> assertThat(offerId.assetIdPart()).isEqualTo(ASSET_ID))
                .map(ContractOfferId::definitionPart)
                .toList();
    }
}
