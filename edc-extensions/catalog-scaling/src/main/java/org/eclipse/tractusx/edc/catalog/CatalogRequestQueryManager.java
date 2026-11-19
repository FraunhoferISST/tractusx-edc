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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    private final int copyBatchSize;

    public CatalogRequestQueryManager(DataSourceRegistry dataSourceRegistry, String dataSourceName,
                                      TransactionContext transactionContext, ObjectMapper objectMapper,
                                      QueryExecutor queryExecutor, WrappedAssetStatements assetStatements,
                                      SqlPolicyStoreStatements policyStatements, int copyBatchSize) {
        super(dataSourceRegistry, dataSourceName, transactionContext, objectMapper, queryExecutor);
        this.assetStatements = assetStatements;
        this.policyStatements = policyStatements;
        this.copyBatchSize = copyBatchSize;
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
     * Creates a temporary table for storing contract definitions. The schema and defaults of the
     * temporary table match the original contract definitions table.
     *
     * @param connection the database connection to use
     * @return the name of the creates temporary table
     */
    public String createTemporaryContractDefinitionTable(Connection connection) {
        return transactionContext.execute(() -> {
            try {
                if (connection.getAutoCommit()) {
                    throw new EdcPersistenceException("Connection must not be in auto-commit mode, as the temporary contract definition table would be dropped immediately.");
                }

                var tableName = "edc_tmp_cd_" + UUID.randomUUID().toString().replace("-", "");
                var sql = """
                        CREATE TEMPORARY TABLE %s
                        (LIKE edc_contract_definitions INCLUDING DEFAULTS)
                        ON COMMIT DROP;
                        """.formatted(tableName);
                queryExecutor.execute(connection, sql);
                return tableName;
            } catch (SQLException e) {
                throw new EdcPersistenceException(format("Failed to create temporary contract definition table: %s", e.getMessage()), e);
            }
        });
    }

    /**
     * Copies contract definitions to a temporary table by access policy IDs.
     *
     * @param connection the database connection to use
     * @param participantContextId the participant context ID
     * @param tempTableName the name of the temporary table
     * @param accessPolicyIds the authorized access policy IDs
     */
    public void copyAuthorizedContractDefinitions(Connection connection, String participantContextId,
                                                  String tempTableName, Collection<String> accessPolicyIds) {
        transactionContext.execute(() -> {
            try {
                var sql = copyToTempTableStatement(tempTableName);
                var ids = accessPolicyIds.toArray(String[]::new);

                for (var fromIndex = 0; fromIndex < ids.length; fromIndex += copyBatchSize) {
                    var toIndex = Math.min(fromIndex + copyBatchSize, ids.length);
                    var sqlArray = connection.createArrayOf("varchar", Arrays.copyOfRange(ids, fromIndex, toIndex));

                    try {
                        queryExecutor.execute(connection, sql, participantContextId, sqlArray);
                    } finally {
                        sqlArray.free();
                    }
                }
            } catch (SQLException e) {
                throw new EdcPersistenceException(format("Failed to copy authorized contract definitions to temp table %s: %s", tempTableName, e.getMessage()), e);
            }
        });
    }

    /**
     * Runs ANALYZE on a temporary table to update the statistics for the query planner, in order to
     * increase performance when joining with the temporary table.
     * <p>
     * ANALYZE is run only on the id and participant context id columns, as the planner only
     * needs the row count and the statistics of the join column (which is participant context id).
     * Sampling the JSON(B)/jsonpath columns would multiply the cost of ANALYZE
     *
     * @param connection the database connection to use
     * @param tempTableName the name of the temporary table
     */
    public void runAnalyze(Connection connection, String tempTableName) {
        transactionContext.execute(() -> {
            try {
                var sql = "ANALYZE %s (contract_definition_id, participant_context_id)".formatted(tempTableName);
                queryExecutor.execute(connection, sql);
            } catch (Exception e) {
                throw new EdcPersistenceException(format("Failed to analyze temp table %s: %s", tempTableName, e.getMessage()), e);
            }
        });
    }

    /**
     * Queries for all assets matching the given QuerySpec filters and with at least one matching
     * contract definition in the given temporary table. Uses the function
     * edc_find_matching_assets_page.
     *
     * @param connection the database connection to use
     * @param tempTableName the name of the temporary table
     * @param querySpec the query spec
     * @return a stream of authorized assets
     */
    public Stream<AuthorizedAsset> queryAssetsWithMatchingContractDefinitions(Connection connection, String tempTableName,
                                                                              QuerySpec querySpec) {
        return transactionContext.execute(() -> {
            var statement = assetStatements.createQuery(querySpec);
            var parameters = WrappedAssetStatements.withTempTableName(tempTableName, statement.getParameters());
            return queryExecutor.query(connection, false, this::mapAssetResultSet, statement.getQueryAsString(), parameters);
        });
    }

    /**
     * Finds a single asset by its ID together with the IDs of the contract definitions in the given
     * temporary table that select it. Returns an empty {@link Optional} if the asset does not exist
     * or is not selected by any of those contract definitions.
     *
     * @param connection the database connection to use
     * @param tempTableName the name of the temporary table
     * @param assetId the ID of the asset
     * @return an {@link Optional} containing the authorized asset if found, or empty if not found
     */
    public Optional<AuthorizedAsset> findAssetWithMatchingContractDefinitions(Connection connection, String tempTableName,
                                                                              String assetId) {
        return transactionContext.execute(() -> {
            var sql = "SELECT * FROM edc_find_authorized_asset(?, ?)";
            try (var assets = queryExecutor.query(connection, false, this::mapAssetResultSet, sql, tempTableName, assetId)) {
                return assets.findFirst();
            }
        });
    }

    private String copyToTempTableStatement(String tempTableName) {
        return """
                INSERT INTO %s (contract_definition_id, created_at, access_policy_id, contract_policy_id, assets_selector, private_properties, participant_context_id,
                                selector_eq_filter, selector_ambiguous_filter, selector_eq_criteria, selector_other_criteria)
                SELECT contract_definition_id, created_at, access_policy_id, contract_policy_id, assets_selector, private_properties, participant_context_id,
                       selector_eq_filter, selector_ambiguous_filter, selector_eq_criteria, selector_other_criteria
                FROM edc_contract_definitions
                WHERE participant_context_id = ? AND access_policy_id = ANY(?::varchar[])
                """.formatted(tempTableName);
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
