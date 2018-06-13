/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.uma.permission.service.dao;

import org.wso2.carbon.database.utils.jdbc.NamedJdbcTemplate;
import org.wso2.carbon.database.utils.jdbc.exceptions.DataAccessException;
import org.wso2.carbon.database.utils.jdbc.exceptions.TransactionException;
import org.wso2.carbon.identity.oauth.uma.common.JdbcUtils;
import org.wso2.carbon.identity.oauth.uma.common.UMAConstants;
import org.wso2.carbon.identity.oauth.uma.common.exception.UMAClientException;
import org.wso2.carbon.identity.oauth.uma.common.exception.UMAServerException;
import org.wso2.carbon.identity.oauth.uma.permission.service.model.PermissionTicketModel;
import org.wso2.carbon.identity.oauth.uma.permission.service.model.Resource;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

/**
 * Data Access Layer functionality for Permission Endpoint. This includes persisting requested permissions
 * (requested resource ids with their scopes) and the issued permission ticket.
 */
public class PermissionTicketDAO {

    private static final String STORE_PT_QUERY = "INSERT INTO IDN_PERMISSION_TICKET " +
            "(PT, TIME_CREATED, VALIDITY_PERIOD, TICKET_STATE, TENANT_ID) VALUES " +
            "(:" + UMAConstants.SQLPlaceholders.PERMISSION_TICKET + ";,:" + UMAConstants.SQLPlaceholders.TIME_CREATED +
            ";,:" + UMAConstants.SQLPlaceholders.VALIDITY_PERIOD + ";,:" + UMAConstants.SQLPlaceholders.STATE + ";,:" +
            UMAConstants.SQLPlaceholders.TENANT_ID + ";)";
    private static final String STORE_PT_RESOURCE_IDS_QUERY = "INSERT INTO IDN_PT_RESOURCE " +
            "(PT_RESOURCE_ID, PT_ID) VALUES " +
            "((SELECT ID FROM IDN_RESOURCE WHERE RESOURCE_ID = :" + UMAConstants.SQLPlaceholders.RESOURCE_ID + ";),:"
            + UMAConstants.SQLPlaceholders.ID + ";)";
    private static final String STORE_PT_RESOURCE_SCOPES_QUERY = "INSERT INTO IDN_PT_RESOURCE_SCOPE " +
            "(PT_RESOURCE_ID, PT_SCOPE_ID) VALUES (:" + UMAConstants.SQLPlaceholders.ID + ";, " +
            "(SELECT ID FROM IDN_RESOURCE_SCOPE WHERE SCOPE_NAME = :" + UMAConstants.SQLPlaceholders.RESOURCE_SCOPE
            + "; AND RESOURCE_IDENTITY = (SELECT ID FROM IDN_RESOURCE WHERE RESOURCE_ID = :" +
            UMAConstants.SQLPlaceholders.RESOURCE_ID + ";)))";
    private static final String VALIDATE_REQUESTED_RESOURCE_IDS_WITH_REGISTERED_RESOURCE_IDS = "SELECT ID " +
            "FROM IDN_RESOURCE WHERE RESOURCE_ID = :" + UMAConstants.SQLPlaceholders.RESOURCE_ID + "; AND " +
            "RESOURCE_OWNER_NAME = :" + UMAConstants.SQLPlaceholders.RESOURCE_OWNER_NAME + "; AND USER_DOMAIN = :" +
            UMAConstants.SQLPlaceholders.USER_DOMAIN + "; AND CLIENT_ID = :" +
            UMAConstants.SQLPlaceholders.CLIENT_ID + ";";
    private static final String VALIDATE_REQUESTED_RESOURCE_SCOPES_WITH_REGISTERED_RESOURCE_SCOPES = "SELECT ID FROM" +
            " IDN_RESOURCE_SCOPE WHERE SCOPE_NAME = :" + UMAConstants.SQLPlaceholders.RESOURCE_SCOPE + "; AND " +
            "RESOURCE_IDENTITY = (SELECT ID FROM IDN_RESOURCE WHERE RESOURCE_ID = :" +
            UMAConstants.SQLPlaceholders.RESOURCE_ID + ";)";

    /**
     * Issue a permission ticket. Permission ticket represents the resources requested by the resource server on
     * client's behalf
     *
     * @param resourceList          A list with the resource ids and the corresponding scopes.
     * @param permissionTicketModel Model class for permission ticket values.
     * @param resourceOwnerName     Resource owner name.
     * @param clientId              Client id representing the resource server.
     * @param userDomain            User domain of the resource owner.
     * @throws UMAServerException Exception thrown when there is a database issue.
     * @throws UMAClientException Exception thrown when there is an invalid resource ID/scope.
     */
    public static void persistPermissionTicket(List<Resource> resourceList, PermissionTicketModel permissionTicketModel,
                                               String resourceOwnerName, String clientId, String userDomain)
            throws UMAServerException, UMAClientException {

        checkResourceIdsExistence(resourceList, resourceOwnerName, clientId, userDomain);
        checkResourceScopesExistence(resourceList);

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        try {
            namedJdbcTemplate.withTransaction(namedTemplate -> {
                int insertedId = namedTemplate.
                        executeInsert(STORE_PT_QUERY,
                                (namedPreparedStatement -> {
                                    namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.PERMISSION_TICKET,
                                            permissionTicketModel.getTicket());
                                    namedPreparedStatement.setTimeStamp(UMAConstants.SQLPlaceholders.TIME_CREATED,
                                            new Timestamp(new Date().getTime()), permissionTicketModel.
                                                    getCreatedTime());
                                    namedPreparedStatement.setLong(UMAConstants.SQLPlaceholders.VALIDITY_PERIOD,
                                            permissionTicketModel.getValidityPeriod());
                                    namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.STATE,
                                            permissionTicketModel.getStatus());
                                    namedPreparedStatement.setLong(UMAConstants.SQLPlaceholders.TENANT_ID,
                                            permissionTicketModel.getTenantId());
                                }), permissionTicketModel, true);
                addRequestedResources(resourceList, insertedId);
                return null;
            });

        } catch (TransactionException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_PT, e);
        }
    }

    private static void checkResourceIdsExistence(List<Resource> resourceList, String
            resourceOwnerName, String clientId, String userDomain) throws UMAClientException,
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        String resourceId;
        for (Resource resource : resourceList) {
            try {
                resourceId = namedJdbcTemplate.fetchSingleRecord(
                        VALIDATE_REQUESTED_RESOURCE_IDS_WITH_REGISTERED_RESOURCE_IDS, (resultSet, rowNumber) ->
                                resultSet.getString(1), namedPreparedStatement -> {
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                    resource.getResourceId());
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_OWNER_NAME,
                                    resourceOwnerName);
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.USER_DOMAIN, userDomain);
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.CLIENT_ID,
                                    clientId);
                        }
                );
                if (resourceId == null) {
                    throw new UMAClientException(UMAConstants.ErrorMessages
                            .ERROR_BAD_REQUEST_INVALID_RESOURCE_ID, "Permission request failed with bad resource ID : "
                            + resource.getResourceId());
                }
            } catch (DataAccessException e) {
                throw new UMAServerException(UMAConstants.ErrorMessages
                        .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_CHECK_RESOURCE_ID_EXISTENCE, e);
            }
        }
    }

    private static void checkResourceScopesExistence(List<Resource> resourceList) throws UMAClientException,
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        String resourceScope;
        for (Resource resource : resourceList) {
            for (String scope : resource.getResourceScopes()) {
                try {
                    resourceScope = namedJdbcTemplate.fetchSingleRecord(
                            VALIDATE_REQUESTED_RESOURCE_SCOPES_WITH_REGISTERED_RESOURCE_SCOPES, (resultSet,
                                                                                                 rowNumber) ->
                                    resultSet.getString(1), namedPreparedStatement -> {
                                namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                        resource.getResourceId());
                                namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_SCOPE, scope);
                            }
                    );
                    if (resourceScope == null) {
                        throw new UMAClientException(UMAConstants.ErrorMessages.ERROR_BAD_REQUEST_INVALID_RESOURCE_SCOPE
                                , "Permission request failed with bad resource scope " + scope + " for resource " +
                                resource.getResourceId());
                    }
                } catch (DataAccessException e) {
                    throw new UMAServerException(UMAConstants.ErrorMessages
                            .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_CHECK_RESOURCE_SCOPE_EXISTENCE, e);
                }
            }
        }
    }

    private static void addRequestedResources(List<Resource> resourceList, int insertedId) throws
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        for (Resource resource : resourceList) {
            try {
                namedJdbcTemplate.withTransaction(namedtemplate -> {
                    int insertedResourceId = namedtemplate.executeInsert(STORE_PT_RESOURCE_IDS_QUERY,
                            (namedpreparedStatement -> {
                                namedpreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                        resource.getResourceId());
                                namedpreparedStatement.setLong(UMAConstants.SQLPlaceholders.ID, insertedId);
                            }), resource, true);
                    addResourceScopes(resource, insertedResourceId);
                    return null;
                });
            } catch (TransactionException e) {
                throw new UMAServerException(UMAConstants.ErrorMessages
                        .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_PT, e);
            }
        }
    }

    private static void addResourceScopes(Resource resource, int insertedResourceId) throws UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        try {
            namedJdbcTemplate.withTransaction(namedtemplate -> {
                namedtemplate.executeBatchInsert(STORE_PT_RESOURCE_SCOPES_QUERY, (namedPreparedStatement -> {
                    namedPreparedStatement.setLong(UMAConstants.SQLPlaceholders.ID,
                            insertedResourceId);
                    for (String scope : resource.getResourceScopes()) {
                        namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                resource.getResourceId());
                        namedPreparedStatement.setString(
                                UMAConstants.SQLPlaceholders.RESOURCE_SCOPE, scope);
                        namedPreparedStatement.addBatch();
                    }
                }), insertedResourceId);
                return null;
            });
        } catch (TransactionException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_PT, e);
        }
    }
}