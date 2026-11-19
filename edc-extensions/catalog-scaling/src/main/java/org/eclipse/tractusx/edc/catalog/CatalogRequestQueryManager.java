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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.asset.spi.domain.DataplaneMetadata;
import org.eclipse.edc.connector.controlplane.policy.spi.PolicyDefinition;
import org.eclipse.edc.connector.controlplane.store.sql.policydefinition.store.schema.SqlPolicyStoreStatements;
import org.eclipse.edc.policy.model.Duty;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.policy.model.PolicyType;
import org.eclipse.edc.policy.model.Prohibition;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.sql.QueryExecutor;
import org.eclipse.edc.sql.store.AbstractSqlStore;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.tractusx.edc.catalog.AuthorizedAsset.MatchingContractDefinition;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * Manages all Postgres queries required for generating a catalog.
 */
public class CatalogRequestQueryManager extends AbstractSqlStore {

    private static final TypeReference<List<Permission>> PERMISSIONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Prohibition>> PROHIBITIONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Duty>> DUTIES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> PROFILES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WrappedAssetStatements assetStatements;
    private final SqlPolicyStoreStatements policyStatements;

    public CatalogRequestQueryManager(DataSourceRegistry dataSourceRegistry, String dataSourceName,
                                      TransactionContext transactionContext, ObjectMapper objectMapper,
                                      QueryExecutor queryExecutor, WrappedAssetStatements assetStatements,
                                      SqlPolicyStoreStatements policyStatements) {
        super(dataSourceRegistry, dataSourceName, transactionContext, objectMapper, queryExecutor);
        this.assetStatements = assetStatements;
        this.policyStatements = policyStatements;
    }

    /**
     * Finds all policies referenced as access policy by at least one contract definition of the
     * given participant context. As upstream, the participant context is only applied to the
     * contract definitions, not to the policies.
     *
     * @param participantContextId the participant context ID
     * @return the referenced access policies, each returned once
     */
    public List<PolicyDefinition> queryAccessPolicies(String participantContextId) {
        return transactionContext.execute(() -> {
            var sql = """
                    SELECT p.* FROM %s p
                    WHERE p.%s IN (SELECT cd.access_policy_id FROM edc_contract_definitions cd WHERE cd.participant_context_id = ?)
                    """.formatted(policyStatements.getPolicyTable(), policyStatements.getPolicyIdColumn());
            try (var policies = queryExecutor.query(getConnection(), true, this::mapPolicyResultSet, sql, participantContextId)) {
                return policies.toList();
            } catch (SQLException e) {
                throw new EdcPersistenceException(format("Failed to query access policies: %s", e.getMessage()), e);
            }
        });
    }

    /**
     * Queries for all assets matching the given QuerySpec filters and with at least one matching
     * contract definition of the participant context, whose access policy is one of the given
     * authorized access policies. Uses the function edc_find_matching_assets_page.
     *
     * @param participantContextId the participant context ID
     * @param accessPolicyIds the authorized access policy IDs
     * @param querySpec the query spec
     * @return the authorized assets
     */
    public List<AuthorizedAsset> queryAssetsWithMatchingContractDefinitions(String participantContextId,
                                                                            Collection<String> accessPolicyIds,
                                                                            QuerySpec querySpec) {
        return transactionContext.execute(() -> {
            var statement = assetStatements.createQuery(querySpec);
            var parameters = WrappedAssetStatements.withAuthorizationParameters(participantContextId,
                    accessPolicyIds.toArray(String[]::new), statement.getParameters());
            try (var assets = queryExecutor.query(getConnection(), true, this::mapAssetResultSet,
                    statement.getQueryAsString(), parameters)) {
                return assets.toList();
            } catch (SQLException e) {
                throw new EdcPersistenceException(format("Failed to query assets: %s", e.getMessage()), e);
            }
        });
    }

    /**
     * Finds a single asset of the participant context by its ID together with the IDs and contract
     * policy IDs of the contract definitions of the participant context, whose access policy is
     * one of the given authorized access policies, that select it. Returns an empty
     * {@link Optional} if the asset does not exist or is not selected by any of those contract
     * definitions.
     *
     * @param participantContextId the participant context ID
     * @param accessPolicyIds the authorized access policy IDs
     * @param assetId the ID of the asset
     * @return an {@link Optional} containing the authorized asset if found, or empty if not found
     */
    public Optional<AuthorizedAsset> findAssetWithMatchingContractDefinitions(String participantContextId,
                                                                              Collection<String> accessPolicyIds,
                                                                              String assetId) {
        return transactionContext.execute(() -> {
            var sql = "SELECT * FROM edc_find_authorized_asset(?, ?::varchar[], ?)";
            try (var assets = queryExecutor.query(getConnection(), true, this::mapAssetResultSet, sql,
                    participantContextId, accessPolicyIds.toArray(String[]::new), assetId)) {
                return assets.findFirst();
            } catch (SQLException e) {
                throw new EdcPersistenceException(format("Failed to find asset %s: %s", assetId, e.getMessage()), e);
            }
        });
    }

    private AuthorizedAsset mapAssetResultSet(ResultSet resultSet) throws SQLException {
        Map<String, Object> dataAddressProperties = fromJson(resultSet.getString("data_address"), getTypeRef());
        var dataAddress = Optional.ofNullable(dataAddressProperties)
                .map(it -> DataAddress.Builder.newInstance().properties(dataAddressProperties).build())
                .orElse(null);

        var asset = Asset.Builder.newInstance()
                .id(resultSet.getString(assetStatements.getAssetIdColumn()))
                .createdAt(resultSet.getLong(assetStatements.getCreatedAtColumn()))
                .properties(fromJson(resultSet.getString(assetStatements.getPropertiesColumn()), getTypeRef()))
                .privateProperties(fromJson(resultSet.getString(assetStatements.getPrivatePropertiesColumn()), getTypeRef()))
                .dataAddress(dataAddress)
                .participantContextId(resultSet.getString(assetStatements.getParticipantContextIdColumn()))
                .dataplaneMetadata(fromJson(resultSet.getString(assetStatements.getDataplaneMetadataColumn()), DataplaneMetadata.class))
                .build();

        var definitionIds = mapArray(resultSet.getArray("matching_definition_ids"));
        var policyIds = mapArray(resultSet.getArray("matching_policy_ids"));
        if (definitionIds.size() != policyIds.size()) {
            throw new EdcPersistenceException("Mismatching number of contract definition and contract policy IDs for asset %s"
                    .formatted(asset.getId()));
        }

        var contractDefinitions = IntStream.range(0, definitionIds.size())
                .mapToObj(i -> new MatchingContractDefinition(definitionIds.get(i), policyIds.get(i)))
                .toList();
        return new AuthorizedAsset(asset, contractDefinitions);
    }

    // mirrors SqlPolicyDefinitionStore#mapResultSet (EDC 0.17.0), which is private upstream
    private PolicyDefinition mapPolicyResultSet(ResultSet resultSet) throws SQLException {
        var policy = Policy.Builder.newInstance()
                .permissions(fromJson(resultSet.getString(policyStatements.getPermissionsColumn()), PERMISSIONS_TYPE))
                .prohibitions(fromJson(resultSet.getString(policyStatements.getProhibitionsColumn()), PROHIBITIONS_TYPE))
                .duties(fromJson(resultSet.getString(policyStatements.getDutiesColumn()), DUTIES_TYPE))
                .profiles(fromJson(resultSet.getString(policyStatements.getProfilesColumn()), PROFILES_TYPE))
                .extensibleProperties(fromJson(resultSet.getString(policyStatements.getExtensiblePropertiesColumn()), MAP_TYPE))
                .inheritsFrom(resultSet.getString(policyStatements.getInheritsFromColumn()))
                .assigner(resultSet.getString(policyStatements.getAssignerColumn()))
                .assignee(resultSet.getString(policyStatements.getAssigneeColumn()))
                .target(resultSet.getString(policyStatements.getTargetColumn()))
                .type(fromJson(resultSet.getString(policyStatements.getTypeColumn()), PolicyType.class))
                .build();

        return PolicyDefinition.Builder.newInstance()
                .id(resultSet.getString(policyStatements.getPolicyIdColumn()))
                .policy(policy)
                .createdAt(resultSet.getLong(policyStatements.getCreatedAtColumn()))
                .privateProperties(fromJson(resultSet.getString(policyStatements.getPrivatePropertiesColumn()), MAP_TYPE))
                .participantContextId(resultSet.getString(policyStatements.getParticipantContextIdColumn()))
                .build();
    }

    private List<String> mapArray(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }

        try {
            return Arrays.asList((String[]) sqlArray.getArray());
        } finally {
            sqlArray.free();
        }
    }
}
