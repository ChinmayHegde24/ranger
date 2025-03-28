/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.rest;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.ranger.biz.AssetMgr;
import org.apache.ranger.biz.RangerBizUtil;
import org.apache.ranger.biz.ServiceDBStore;
import org.apache.ranger.biz.SessionMgr;
import org.apache.ranger.biz.XUserMgr;
import org.apache.ranger.common.ContextUtil;
import org.apache.ranger.common.MessageEnums;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.common.RangerConstants;
import org.apache.ranger.common.SearchCriteria;
import org.apache.ranger.common.SearchUtil;
import org.apache.ranger.common.ServiceUtil;
import org.apache.ranger.common.StringUtil;
import org.apache.ranger.common.UserSessionBase;
import org.apache.ranger.common.annotation.RangerAnnotationClassName;
import org.apache.ranger.common.annotation.RangerAnnotationJSMgrName;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.XXGroup;
import org.apache.ranger.entity.XXService;
import org.apache.ranger.entity.XXServiceDef;
import org.apache.ranger.plugin.model.RangerPluginInfo;
import org.apache.ranger.plugin.model.RangerPrincipal;
import org.apache.ranger.plugin.model.RangerService;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.plugin.util.RangerRESTUtils;
import org.apache.ranger.plugin.util.RangerUserStore;
import org.apache.ranger.security.context.RangerAPIList;
import org.apache.ranger.service.AuthSessionService;
import org.apache.ranger.service.XAuditMapService;
import org.apache.ranger.service.XGroupPermissionService;
import org.apache.ranger.service.XGroupService;
import org.apache.ranger.service.XGroupUserService;
import org.apache.ranger.service.XModuleDefService;
import org.apache.ranger.service.XPermMapService;
import org.apache.ranger.service.XResourceService;
import org.apache.ranger.service.XUserPermissionService;
import org.apache.ranger.service.XUserService;
import org.apache.ranger.ugsyncutil.model.GroupUserInfo;
import org.apache.ranger.ugsyncutil.model.UsersGroupRoleAssignments;
import org.apache.ranger.view.VXAuditMap;
import org.apache.ranger.view.VXAuditMapList;
import org.apache.ranger.view.VXAuthSession;
import org.apache.ranger.view.VXAuthSessionList;
import org.apache.ranger.view.VXGroup;
import org.apache.ranger.view.VXGroupList;
import org.apache.ranger.view.VXGroupPermission;
import org.apache.ranger.view.VXGroupPermissionList;
import org.apache.ranger.view.VXGroupUser;
import org.apache.ranger.view.VXGroupUserInfo;
import org.apache.ranger.view.VXGroupUserList;
import org.apache.ranger.view.VXLong;
import org.apache.ranger.view.VXModuleDef;
import org.apache.ranger.view.VXModuleDefList;
import org.apache.ranger.view.VXModulePermissionList;
import org.apache.ranger.view.VXPermMap;
import org.apache.ranger.view.VXPermMapList;
import org.apache.ranger.view.VXString;
import org.apache.ranger.view.VXStringList;
import org.apache.ranger.view.VXUgsyncAuditInfo;
import org.apache.ranger.view.VXUser;
import org.apache.ranger.view.VXUserGroupInfo;
import org.apache.ranger.view.VXUserList;
import org.apache.ranger.view.VXUserPermission;
import org.apache.ranger.view.VXUserPermissionList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.ranger.common.RangerCommonEnums.GROUP_EXTERNAL;
import static org.apache.ranger.common.RangerCommonEnums.USER_EXTERNAL;

@Path("xusers")
@Component
@Scope("request")
@RangerAnnotationJSMgrName("XUserMgr")
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class XUserREST {
    static final Logger logger = LoggerFactory.getLogger(XUserREST.class);

    public static final String USERSTORE_DOWNLOAD_USERS = "userstore.download.auth.users";

    @Autowired
    SearchUtil searchUtil;

    @Autowired
    XUserMgr xUserMgr;

    @Autowired
    XGroupService xGroupService;

    @Autowired
    XModuleDefService xModuleDefService;

    @Autowired
    XUserPermissionService xUserPermissionService;

    @Autowired
    XGroupPermissionService xGroupPermissionService;

    @Autowired
    XUserService xUserService;

    @Autowired
    XGroupUserService xGroupUserService;

    @Autowired
    XPermMapService xPermMapService;

    @Autowired
    XAuditMapService xAuditMapService;

    @Autowired
    RESTErrorUtil restErrorUtil;

    @Autowired
    RangerDaoManager rangerDaoManager;

    @Autowired
    SessionMgr sessionMgr;

    @Autowired
    AuthSessionService authSessionService;

    @Autowired
    RangerBizUtil bizUtil;

    @Autowired
    XResourceService xResourceService;

    @Autowired
    StringUtil stringUtil;

    @Autowired
    AssetMgr assetMgr;

    @Autowired
    ServiceUtil serviceUtil;

    @Autowired
    ServiceDBStore svcStore;

    // Handle XGroup
    @GET
    @Path("/groups/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_GROUP + "\")")
    public VXGroup getXGroup(@PathParam("id") Long id) {
        return xUserMgr.getXGroup(id);
    }

    @GET
    @Path("/secure/groups/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SECURE_GET_X_GROUP + "\")")
    public VXGroup secureGetXGroup(@PathParam("id") Long id) {
        return xUserMgr.getXGroup(id);
    }

    @POST
    @Path("/groups")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXGroup createXGroup(VXGroup vXGroup) {
        return xUserMgr.createXGroupWithoutLogin(vXGroup);
    }

    @POST
    @Path("/groups/groupinfo")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXGroupUserInfo createXGroupUserFromMap(VXGroupUserInfo vXGroupUserInfo) {
        return xUserMgr.createXGroupUserFromMap(vXGroupUserInfo);
    }

    @POST
    @Path("/secure/groups")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXGroup secureCreateXGroup(VXGroup vXGroup) {
        return xUserMgr.createXGroup(vXGroup);
    }

    @PUT
    @Path("/groups")
    @Consumes("application/json")
    @Produces("application/json")
    public VXGroup updateXGroup(VXGroup vXGroup) {
        return xUserMgr.updateXGroup(vXGroup);
    }

    @PUT
    @Path("/secure/groups/{id}")
    @Consumes("application/json")
    @Produces("application/json")
    public VXGroup secureUpdateXGroup(VXGroup vXGroup) {
        return xUserMgr.updateXGroup(vXGroup);
    }

    @PUT
    @Path("/secure/groups/visibility")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.MODIFY_GROUPS_VISIBILITY + "\")")
    public void modifyGroupsVisibility(HashMap<Long, Integer> groupVisibilityMap) {
        xUserMgr.modifyGroupsVisibility(groupVisibilityMap);
    }

    @DELETE
    @Path("/groups/{id}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    @RangerAnnotationClassName(class_name = VXGroup.class)
    public void deleteXGroup(@PathParam("id") Long id, @Context HttpServletRequest request) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = !StringUtils.isEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr.trim());

        xUserMgr.deleteXGroup(id, forceDelete);
    }

    /**
     * Implements the traditional search functionalities for XGroups
     *
     * @param request
     * @return
     */
    @GET
    @Path("/groups")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_GROUPS + "\")")
    public VXGroupList searchXGroups(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupService.sortFields);

        searchUtil.extractString(request, searchCriteria, "name", "group name", null);
        searchUtil.extractInt(request, searchCriteria, "isVisible", "Group Visibility");
        searchUtil.extractInt(request, searchCriteria, "groupSource", "group source");
        searchUtil.extractString(request, searchCriteria, "syncSource", "Sync Source", null);

        return xUserMgr.searchXGroups(searchCriteria);
    }

    @GET
    @Path("/groups/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_GROUPS + "\")")
    public VXLong countXGroups(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupService.sortFields);

        return xUserMgr.getXGroupSearchCount(searchCriteria);
    }

    // Handle XUser
    @GET
    @Path("/users/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_USER + "\")")
    public VXUser getXUser(@PathParam("id") Long id) {
        return xUserMgr.getXUser(id);
    }

    @GET
    @Path("/secure/users/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SECURE_GET_X_USER + "\")")
    public VXUser secureGetXUser(@PathParam("id") Long id) {
        return xUserMgr.getXUser(id);
    }

    @POST
    @Path("/users")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXUser createXUser(VXUser vXUser) {
        return xUserMgr.createXUserWithOutLogin(vXUser);
    }

    @POST
    @Path("/users/external")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXUser createExternalUser(VXUser vXUser) {
        return xUserMgr.createExternalUser(vXUser.getName());
    }

    @POST
    @Path("/users/userinfo")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXUserGroupInfo createXUserGroupFromMap(VXUserGroupInfo vXUserGroupInfo) {
        return xUserMgr.createXUserGroupFromMap(vXUserGroupInfo);
    }

    @POST
    @Path("/secure/users")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXUser secureCreateXUser(VXUser vXUser) {
        bizUtil.checkUserAccessible(vXUser);

        return xUserMgr.createXUser(vXUser);
    }

    @PUT
    @Path("/users")
    @Consumes("application/json")
    @Produces("application/json")
    public VXUser updateXUser(VXUser vXUser) {
        bizUtil.checkUserAccessible(vXUser);

        return xUserMgr.updateXUser(vXUser);
    }

    @PUT
    @Path("/secure/users/{id}")
    @Consumes("application/json")
    @Produces("application/json")
    public VXUser secureUpdateXUser(VXUser vXUser) {
        bizUtil.checkUserAccessible(vXUser);

        return xUserMgr.updateXUser(vXUser);
    }

    @PUT
    @Path("/secure/users/visibility")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.MODIFY_USER_VISIBILITY + "\")")
    public void modifyUserVisibility(HashMap<Long, Integer> visibilityMap) {
        xUserMgr.modifyUserVisibility(visibilityMap);
    }

    @DELETE
    @Path("/users/{id}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    @RangerAnnotationClassName(class_name = VXUser.class)
    public void deleteXUser(@PathParam("id") Long id, @Context HttpServletRequest request) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = !StringUtils.isEmpty(forceDeleteStr) && forceDeleteStr.equalsIgnoreCase("true");

        xUserMgr.deleteXUser(id, forceDelete);
    }

    /**
     * Implements the traditional search functionalities for XUsers
     *
     * @param request
     * @return
     */
    @GET
    @Path("/users")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_USERS + "\")")
    public VXUserList searchXUsers(@Context HttpServletRequest request, @QueryParam("syncSource") String syncSource, @QueryParam("userRole") String userRole) {
        String         userRoleParamName = RangerConstants.ROLE_USER;
        SearchCriteria searchCriteria    = searchUtil.extractCommonCriterias(request, xUserService.sortFields);
        String         userName          = null;

        if (request.getUserPrincipal() != null) {
            userName = request.getUserPrincipal().getName();
        }

        searchUtil.extractString(request, searchCriteria, "name", "User name", null);
        searchUtil.extractString(request, searchCriteria, "emailAddress", "Email Address", null);
        searchUtil.extractInt(request, searchCriteria, "userSource", "User Source");
        searchUtil.extractInt(request, searchCriteria, "isVisible", "User Visibility");
        searchUtil.extractInt(request, searchCriteria, "status", "User Status");

        List<String> userRolesList = searchUtil.extractStringList(request, searchCriteria, "userRoleList", "User Role List", "userRoleList", null, null);

        searchUtil.extractRoleString(request, searchCriteria, "userRole", "Role", null);
        searchUtil.extractString(request, searchCriteria, "syncSource", "Sync Source", null);

        if (CollectionUtils.isNotEmpty(userRolesList) && CollectionUtils.size(userRolesList) == 1 && userRolesList.get(0).equalsIgnoreCase(userRoleParamName)) {
            if (!(searchCriteria.getParamList().containsKey("name"))) {
                searchCriteria.addParam("name", userName);
            } else if ((searchCriteria.getParamList().containsKey("name")) && userName != null && userName.contains((String) searchCriteria.getParamList().get("name"))) {
                searchCriteria.addParam("name", userName);
            }
        }

        UserSessionBase userSession = ContextUtil.getCurrentUserSession();

        if (userSession != null && userSession.getLoginId() != null) {
            VXUser loggedInVXUser = xUserService.getXUserByUserName(userSession.getLoginId());

            if (loggedInVXUser != null && loggedInVXUser.getUserRoleList().size() == 1) {
                if (loggedInVXUser.getUserRoleList().contains(RangerConstants.ROLE_SYS_ADMIN) || loggedInVXUser.getUserRoleList().contains(RangerConstants.ROLE_ADMIN_AUDITOR)) {
                    boolean hasRole = false;

                    hasRole = !userRolesList.contains(RangerConstants.ROLE_SYS_ADMIN) ? userRolesList.add(RangerConstants.ROLE_SYS_ADMIN) : hasRole;
                    hasRole = !userRolesList.contains(RangerConstants.ROLE_ADMIN_AUDITOR) ? userRolesList.add(RangerConstants.ROLE_ADMIN_AUDITOR) : hasRole;
                    hasRole = !userRolesList.contains(RangerConstants.ROLE_USER) ? userRolesList.add(RangerConstants.ROLE_USER) : hasRole;

                    if (loggedInVXUser.getUserRoleList().contains(RangerConstants.ROLE_SYS_ADMIN) && "rangerusersync".equalsIgnoreCase(userSession.getLoginId())) {
                        hasRole = !userRolesList.contains(RangerConstants.ROLE_KEY_ADMIN) ? userRolesList.add(RangerConstants.ROLE_KEY_ADMIN) : hasRole;
                        hasRole = !userRolesList.contains(RangerConstants.ROLE_KEY_ADMIN_AUDITOR) ? userRolesList.add(RangerConstants.ROLE_KEY_ADMIN_AUDITOR) : hasRole;
                    }
                } else if (loggedInVXUser.getUserRoleList().contains(RangerConstants.ROLE_KEY_ADMIN) || loggedInVXUser.getUserRoleList().contains(RangerConstants.ROLE_KEY_ADMIN_AUDITOR)) {
                    boolean hasRole = false;

                    hasRole = !userRolesList.contains(RangerConstants.ROLE_KEY_ADMIN) ? userRolesList.add(RangerConstants.ROLE_KEY_ADMIN) : hasRole;
                    hasRole = !userRolesList.contains(RangerConstants.ROLE_KEY_ADMIN_AUDITOR) ? userRolesList.add(RangerConstants.ROLE_KEY_ADMIN_AUDITOR) : hasRole;
                    hasRole = !userRolesList.contains(RangerConstants.ROLE_USER) ? userRolesList.add(RangerConstants.ROLE_USER) : hasRole;
                } else if (loggedInVXUser.getUserRoleList().contains(RangerConstants.ROLE_USER)) {
                    logger.info("Logged-In user having user role will be able to fetch his own user details.");

                    if (!searchCriteria.getParamList().containsKey("name")) {
                        searchCriteria.addParam("name", loggedInVXUser.getName());
                    } else if (searchCriteria.getParamList().containsKey("name") && !stringUtil.isEmpty(searchCriteria.getParamValue("name").toString()) && !searchCriteria.getParamValue("name").toString().equalsIgnoreCase(loggedInVXUser.getName())) {
                        throw restErrorUtil.create403RESTException("Logged-In user is not allowed to access requested user data.");
                    }
                }
            }
        }

        return xUserMgr.searchXUsers(searchCriteria);
    }

    @GET
    @Path("/lookup/users")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_USERS_LOOKUP + "\")")
    public VXStringList getUsersLookup(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xUserService.sortFields);
        VXStringList   ret            = new VXStringList();
        List<VXString> vXList         = new ArrayList<>();

        searchUtil.extractString(request, searchCriteria, "name", "User name", null);
        searchUtil.extractInt(request, searchCriteria, "isVisible", "User Visibility");

        try {
            VXUserList vXUserList = xUserMgr.lookupXUsers(searchCriteria);

            for (VXUser vxUser : vXUserList.getList()) {
                VXString vXString = new VXString();

                vXString.setValue(vxUser.getName());

                vXList.add(vXString);
            }

            ret.setVXStrings(vXList);
            ret.setPageSize(vXUserList.getPageSize());
            ret.setTotalCount(vXUserList.getTotalCount());
            ret.setSortType(vXUserList.getSortType());
            ret.setSortBy(vXUserList.getSortBy());
        } catch (Throwable excp) {
            throw restErrorUtil.createRESTException(excp.getMessage());
        }

        return ret;
    }

    @GET
    @Path("/lookup/groups")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_GROUPS_LOOKUP + "\")")
    public VXStringList getGroupsLookup(@Context HttpServletRequest request) {
        VXStringList   ret            = new VXStringList();
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupService.sortFields);
        List<VXString> vXList         = new ArrayList<>();

        searchUtil.extractString(request, searchCriteria, "name", "group name", null);
        searchUtil.extractInt(request, searchCriteria, "isVisible", "Group Visibility");

        try {
            VXGroupList vXGroupList = xUserMgr.lookupXGroups(searchCriteria);

            for (VXGroup vxGroup : vXGroupList.getList()) {
                VXString vXString = new VXString();

                vXString.setValue(vxGroup.getName());

                vXList.add(vXString);
            }

            ret.setVXStrings(vXList);
            ret.setPageSize(vXGroupList.getPageSize());
            ret.setTotalCount(vXGroupList.getTotalCount());
            ret.setSortType(vXGroupList.getSortType());
            ret.setSortBy(vXGroupList.getSortBy());
        } catch (Throwable excp) {
            throw restErrorUtil.createRESTException(excp.getMessage());
        }

        return ret;
    }

    @GET
    @Path("/lookup/principals")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_PRINCIPALS_LOOKUP + "\")")
    public List<RangerPrincipal> getPrincipalsLookup(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupService.sortFields);

        searchUtil.extractString(request, searchCriteria, "name", null, null);

        return xUserMgr.getRangerPrincipals(searchCriteria);
    }

    @GET
    @Path("/users/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_USERS + "\")")
    public VXLong countXUsers(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xUserService.sortFields);

        return xUserMgr.getXUserSearchCount(searchCriteria);
    }

    // Handle XGroupUser
    @GET
    @Path("/groupusers/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_GROUP_USER + "\")")
    public VXGroupUser getXGroupUser(@PathParam("id") Long id) {
        return xUserMgr.getXGroupUser(id);
    }

    @POST
    @Path("/groupusers")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXGroupUser createXGroupUser(VXGroupUser vXGroupUser) {
        if (vXGroupUser == null || StringUtils.isBlank(vXGroupUser.getName()) || vXGroupUser.getUserId() == null) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_BAD_REQUEST, "Group name or UserId is empty or null", true);
        }

        return xUserMgr.createXGroupUser(vXGroupUser);
    }

    @PUT
    @Path("/groupusers")
    @Consumes("application/json")
    @Produces("application/json")
    public VXGroupUser updateXGroupUser(VXGroupUser vXGroupUser) {
        if (vXGroupUser == null || StringUtils.isBlank(vXGroupUser.getName()) || vXGroupUser.getUserId() == null) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_BAD_REQUEST, "Group name or UserId is empty or null", true);
        }

        return xUserMgr.updateXGroupUser(vXGroupUser);
    }

    @DELETE
    @Path("/groupusers/{id}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    @RangerAnnotationClassName(class_name = VXGroupUser.class)
    public void deleteXGroupUser(@PathParam("id") Long id, @Context HttpServletRequest request) {
        boolean force = true;

        xUserMgr.deleteXGroupUser(id, force);
    }

    /**
     * Implements the traditional search functionalities for XGroupUsers
     *
     * @param request
     * @return
     */
    @GET
    @Path("/groupusers")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_GROUP_USERS + "\")")
    public VXGroupUserList searchXGroupUsers(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupUserService.sortFields);

        return xUserMgr.searchXGroupUsers(searchCriteria);
    }

    /**
     * Implements the traditional search functionalities for XGroupUsers by Group name
     *
     * @param request
     * @return
     */
    @GET
    @Path("/groupusers/groupName/{groupName}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_GROUP_USERS_BY_GROUP_NAME + "\")")
    public VXGroupUserInfo getXGroupUsersByGroupName(@Context HttpServletRequest request, @PathParam("groupName") String groupName) {
        return xUserMgr.getXGroupUserFromMap(groupName);
    }

    @GET
    @Path("/groupusers/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_GROUP_USERS + "\")")
    public VXLong countXGroupUsers(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupUserService.sortFields);

        return xUserMgr.getXGroupUserSearchCount(searchCriteria);
    }

    // Handle XPermMap
    @GET
    @Path("/permmaps/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_PERM_MAP + "\")")
    public VXPermMap getXPermMap(@PathParam("id") Long id) {
        VXPermMap permMap = xUserMgr.getXPermMap(id);

        if (permMap != null) {
            if (xResourceService.readResource(permMap.getResourceId()) == null) {
                throw restErrorUtil.createRESTException("Invalid Input Data - No resource found with Id: " + permMap.getResourceId(), MessageEnums.INVALID_INPUT_DATA);
            }
        }

        return permMap;
    }

    @POST
    @Path("/permmaps")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.CREATE_X_PERM_MAP + "\")")
    public VXPermMap createXPermMap(VXPermMap vXPermMap) {
        if (vXPermMap != null) {
            if (xResourceService.readResource(vXPermMap.getResourceId()) == null) {
                throw restErrorUtil.createRESTException("Invalid Input Data - No resource found with Id: " + vXPermMap.getResourceId(), MessageEnums.INVALID_INPUT_DATA);
            }
        }

        return xUserMgr.createXPermMap(vXPermMap);
    }

    @PUT
    @Path("/permmaps")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.UPDATE_X_PERM_MAP + "\")")
    public VXPermMap updateXPermMap(VXPermMap vXPermMap) {
        VXPermMap vXPermMapRet = null;

        if (vXPermMap != null) {
            if (xResourceService.readResource(vXPermMap.getResourceId()) == null) {
                throw restErrorUtil.createRESTException("Invalid Input Data - No resource found with Id: " + vXPermMap.getResourceId());
            } else {
                vXPermMapRet = xUserMgr.updateXPermMap(vXPermMap);
            }
        }

        return vXPermMapRet;
    }

    @DELETE
    @Path("/permmaps/{id}")
    @RangerAnnotationClassName(class_name = VXPermMap.class)
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.DELETE_X_PERM_MAP + "\")")
    public void deleteXPermMap(@PathParam("id") Long id, @Context HttpServletRequest request) {
        boolean force = false;

        xUserMgr.deleteXPermMap(id, force);
    }

    /**
     * Implements the traditional search functionalities for XPermMaps
     *
     * @param request
     * @return
     */
    @GET
    @Path("/permmaps")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_PERM_MAPS + "\")")
    public VXPermMapList searchXPermMaps(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xPermMapService.sortFields);

        return xUserMgr.searchXPermMaps(searchCriteria);
    }

    @GET
    @Path("/permmaps/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_PERM_MAPS + "\")")
    public VXLong countXPermMaps(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xPermMapService.sortFields);

        return xUserMgr.getXPermMapSearchCount(searchCriteria);
    }

    // Handle XAuditMap
    @GET
    @Path("/auditmaps/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_AUDIT_MAP + "\")")
    public VXAuditMap getXAuditMap(@PathParam("id") Long id) {
        VXAuditMap vXAuditMap = xUserMgr.getXAuditMap(id);

        if (vXAuditMap != null) {
            if (xResourceService.readResource(vXAuditMap.getResourceId()) == null) {
                throw restErrorUtil.createRESTException("Invalid Input Data - No resource found with Id: " + vXAuditMap.getResourceId(), MessageEnums.INVALID_INPUT_DATA);
            }
        }

        return vXAuditMap;
    }

    @POST
    @Path("/auditmaps")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.CREATE_X_AUDIT_MAP + "\")")
    public VXAuditMap createXAuditMap(VXAuditMap vXAuditMap) {
        if (vXAuditMap != null) {
            if (xResourceService.readResource(vXAuditMap.getResourceId()) == null) {
                throw restErrorUtil.createRESTException("Invalid Input Data - No resource found with Id: " + vXAuditMap.getResourceId(), MessageEnums.INVALID_INPUT_DATA);
            }
        }

        return xUserMgr.createXAuditMap(vXAuditMap);
    }

    @PUT
    @Path("/auditmaps")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.UPDATE_X_AUDIT_MAP + "\")")
    public VXAuditMap updateXAuditMap(VXAuditMap vXAuditMap) {
        VXAuditMap vXAuditMapRet = null;

        if (vXAuditMap != null) {
            if (xResourceService.readResource(vXAuditMap.getResourceId()) == null) {
                throw restErrorUtil.createRESTException("Invalid Input Data - No resource found with Id: " + vXAuditMap.getResourceId(), MessageEnums.INVALID_INPUT_DATA);
            } else {
                vXAuditMapRet = xUserMgr.updateXAuditMap(vXAuditMap);
            }
        }

        return vXAuditMapRet;
    }

    @DELETE
    @Path("/auditmaps/{id}")
    @RangerAnnotationClassName(class_name = VXAuditMap.class)
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.DELETE_X_AUDIT_MAP + "\")")
    public void deleteXAuditMap(@PathParam("id") Long id, @Context HttpServletRequest request) {
        boolean force = false;

        xUserMgr.deleteXAuditMap(id, force);
    }

    /**
     * Implements the traditional search functionalities for XAuditMaps
     *
     * @param request
     * @return
     */
    @GET
    @Path("/auditmaps")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_AUDIT_MAPS + "\")")
    public VXAuditMapList searchXAuditMaps(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xAuditMapService.sortFields);

        return xUserMgr.searchXAuditMaps(searchCriteria);
    }

    @GET
    @Path("/auditmaps/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_AUDIT_MAPS + "\")")
    public VXLong countXAuditMaps(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xAuditMapService.sortFields);

        return xUserMgr.getXAuditMapSearchCount(searchCriteria);
    }

    // Handle XUser
    @GET
    @Path("/users/userName/{userName}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_USER_BY_USER_NAME + "\")")
    public VXUser getXUserByUserName(@Context HttpServletRequest request, @PathParam("userName") String userName) {
        return xUserMgr.getXUserByUserName(userName);
    }

    @GET
    @Path("/groups/groupName/{groupName}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_GROUP_BY_GROUP_NAME + "\")")
    public VXGroup getXGroupByGroupName(@Context HttpServletRequest request, @PathParam("groupName") String groupName) {
        VXGroup         vXGroup     = xGroupService.getGroupByGroupName(groupName);
        UserSessionBase userSession = ContextUtil.getCurrentUserSession();

        if (userSession != null && userSession.getLoginId() != null && userSession.getUserRoleList().contains(RangerConstants.ROLE_USER)) {
            VXUser  loggedInVXUser = xUserService.getXUserByUserName(userSession.getLoginId());
            boolean isMatch        = false;

            if (loggedInVXUser != null && vXGroup != null) {
                List<XXGroup> userGroups = xGroupService.getGroupsByUserId(loggedInVXUser.getId());

                for (XXGroup xXGroup : userGroups) {
                    if (xXGroup != null && StringUtils.equals(xXGroup.getName(), vXGroup.getName())) {
                        isMatch = true;
                        break;
                    }
                }
            }

            if (!isMatch) {
                vXGroup = null;
            }
        }

        return vXGroup;
    }

    @DELETE
    @Path("/users/userName/{userName}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteXUserByUserName(@PathParam("userName") String userName, @Context HttpServletRequest request) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = !StringUtils.isEmpty(forceDeleteStr) && forceDeleteStr.equalsIgnoreCase("true");
        VXUser  vxUser         = xUserService.getXUserByUserName(userName);

        xUserMgr.deleteXUser(vxUser.getId(), forceDelete);
    }

    /**
     * Proceed with <tt>caution</tt>: Force deletes users from the ranger db,
     * <tt>Delete</tt> happens one at a time with immediate commit on the transaction.
     */
    @DELETE
    @Path("/delete/external/users")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    @Produces("application/json")
    public Response forceDeleteExternalUsers(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = new SearchCriteria();

        searchUtil.extractString(request, searchCriteria, "name", "User name", null);
        searchUtil.extractString(request, searchCriteria, "emailAddress", "Email Address", null);
        searchUtil.extractInt(request, searchCriteria, "isVisible", "User Visibility");
        searchUtil.extractInt(request, searchCriteria, "status", "User Status");
        searchUtil.extractString(request, searchCriteria, "syncSource", "Sync Source", null);
        searchUtil.extractRoleString(request, searchCriteria, "userRole", "Role", null);

        // for invalid params
        if (request.getQueryString() != null && searchCriteria.getParamList().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid query params!").build();
        }

        // only for external users
        searchCriteria.addParam("userSource", USER_EXTERNAL);

        List<Long> userIds      = xUserService.searchXUsersForIds(searchCriteria);
        long       usersDeleted = xUserMgr.forceDeleteExternalUsers(userIds);
        String     response     = "No users were deleted!";

        if (usersDeleted == 1) {
            response = "1 user deleted successfully.";
        } else if (usersDeleted > 0) {
            response = String.format("%d users deleted successfully.", usersDeleted);
        }

        return Response.ok(response).build();
    }

    /**
     * Proceed with <tt>caution</tt>: Force deletes groups from the ranger db,
     * <tt>Delete</tt> happens one at a time with immediate commit on the transaction.
     */
    @DELETE
    @Path("/delete/external/groups")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    @Produces("application/json")
    public Response forceDeleteExternalGroups(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = new SearchCriteria();

        searchUtil.extractString(request, searchCriteria, "name", "Group Name", null);
        searchUtil.extractInt(request, searchCriteria, "isVisible", "Group Visibility");
        searchUtil.extractString(request, searchCriteria, "syncSource", "Sync Source", null);

        // for invalid params
        if (request.getQueryString() != null && searchCriteria.getParamList().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid query params!").build();
        }

        // only for external groups
        searchCriteria.addParam("groupSource", GROUP_EXTERNAL);

        List<Long> groupIds      = xGroupService.searchXGroupsForIds(searchCriteria);
        long       groupsDeleted = xUserMgr.forceDeleteExternalGroups(groupIds);
        String     response      = "No groups were deleted!";

        if (groupsDeleted == 1) {
            response = "1 group deleted successfully.";
        } else if (groupsDeleted > 0) {
            response = String.format("%d groups deleted successfully.", groupsDeleted);
        }

        return Response.ok(response).build();
    }

    @DELETE
    @Path("/groups/groupName/{groupName}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteXGroupByGroupName(@PathParam("groupName") String groupName, @Context HttpServletRequest request) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = !StringUtils.isEmpty(forceDeleteStr) && forceDeleteStr.equalsIgnoreCase("true");
        VXGroup vxGroup        = xGroupService.getGroupByGroupName(groupName);

        xUserMgr.deleteXGroup(vxGroup.getId(), forceDelete);
    }

    @DELETE
    @Path("/group/{groupName}/user/{userName}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteXGroupAndXUser(@PathParam("groupName") String groupName, @PathParam("userName") String userName, @Context HttpServletRequest request) {
        xUserMgr.deleteXGroupAndXUser(groupName, userName);
    }

    @GET
    @Path("/{userId}/groups")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_USER_GROUPS + "\")")
    public VXGroupList getXUserGroups(@Context HttpServletRequest request, @PathParam("userId") Long id) {
        return xUserMgr.getXUserGroups(id);
    }

    @GET
    @Path("/{groupId}/users")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_GROUP_USERS + "\")")
    public VXUserList getXGroupUsers(@Context HttpServletRequest request, @PathParam("groupId") Long id) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupUserService.sortFields);

        searchCriteria.addParam("xGroupId", id);

        return xUserMgr.getXGroupUsers(searchCriteria);
    }

    @GET
    @Path("/authSessions")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_AUTH_SESSIONS + "\")")
    public VXAuthSessionList getAuthSessions(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, AuthSessionService.AUTH_SESSION_SORT_FLDS);

        searchUtil.extractLong(request, searchCriteria, "id", "Auth Session Id");
        searchUtil.extractLong(request, searchCriteria, "userId", "User Id");
        searchUtil.extractInt(request, searchCriteria, "authStatus", "Auth Status");
        searchUtil.extractInt(request, searchCriteria, "authType", "Login Type");
        searchUtil.extractInt(request, searchCriteria, "deviceType", "Device Type");
        searchUtil.extractString(request, searchCriteria, "firstName", "User First Name", StringUtil.VALIDATION_NAME);
        searchUtil.extractString(request, searchCriteria, "lastName", "User Last Name", StringUtil.VALIDATION_NAME);
        searchUtil.extractString(request, searchCriteria, "requestUserAgent", "User Agent", StringUtil.VALIDATION_TEXT);
        searchUtil.extractString(request, searchCriteria, "requestIP", "Request IP Address", StringUtil.VALIDATION_IP_ADDRESS);
        searchUtil.extractString(request, searchCriteria, "loginId", "Login ID", StringUtil.VALIDATION_TEXT);
        searchUtil.extractDate(request, searchCriteria, "startDate", "Start Date", null);
        searchUtil.extractDate(request, searchCriteria, "endDate", "End Date", null);

        return sessionMgr.searchAuthSessions(searchCriteria);
    }

    @GET
    @Path("/authSessions/info")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_AUTH_SESSION + "\")")
    public VXAuthSession getAuthSession(@Context HttpServletRequest request) {
        String authSessionId = request.getParameter("extSessionId");

        return sessionMgr.getAuthSessionBySessionId(authSessionId);
    }

    // Handle module permissions
    @POST
    @Path("/permission")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.CREATE_X_MODULE_DEF_PERMISSION + "\")")
    public VXModuleDef createXModuleDefPermission(VXModuleDef vXModuleDef) {
        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();

        return xUserMgr.createXModuleDefPermission(vXModuleDef);
    }

    @GET
    @Path("/permission/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_MODULE_DEF_PERMISSION + "\")")
    public VXModuleDef getXModuleDefPermission(@PathParam("id") Long id) {
        return xUserMgr.getXModuleDefPermission(id);
    }

    @PUT
    @Path("/permission/{id}")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.UPDATE_X_MODULE_DEF_PERMISSION + "\")")
    public VXModuleDef updateXModuleDefPermission(VXModuleDef vXModuleDef) {
        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();

        return xUserMgr.updateXModuleDefPermission(vXModuleDef);
    }

    @DELETE
    @Path("/permission/{id}")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.DELETE_X_MODULE_DEF_PERMISSION + "\")")
    public void deleteXModuleDefPermission(@PathParam("id") Long id, @Context HttpServletRequest request) {
        boolean force = true;

        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();
        xUserMgr.deleteXModuleDefPermission(id, force);
    }

    @GET
    @Path("/permission")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_MODULE_DEF + "\")")
    public VXModuleDefList searchXModuleDef(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xModuleDefService.sortFields);

        searchUtil.extractString(request, searchCriteria, "module", "modulename", null);
        searchUtil.extractString(request, searchCriteria, "moduleDefList", "id", null);
        searchUtil.extractString(request, searchCriteria, "userName", "userName", null);
        searchUtil.extractString(request, searchCriteria, "groupName", "groupName", null);

        return xUserMgr.searchXModuleDef(searchCriteria);
    }

    @GET
    @Path("/permissionlist")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_MODULE_DEF + "\")")
    public VXModulePermissionList searchXModuleDefList(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xModuleDefService.sortFields);

        searchUtil.extractString(request, searchCriteria, "module", "modulename", null);
        searchUtil.extractString(request, searchCriteria, "moduleDefList", "id", null);
        searchUtil.extractString(request, searchCriteria, "userName", "userName", null);
        searchUtil.extractString(request, searchCriteria, "groupName", "groupName", null);

        return xUserMgr.searchXModuleDefList(searchCriteria);
    }

    @GET
    @Path("/permission/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_MODULE_DEF + "\")")
    public VXLong countXModuleDef(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xModuleDefService.sortFields);

        return xUserMgr.getXModuleDefSearchCount(searchCriteria);
    }

    // Handle user permissions
    @POST
    @Path("/permission/user")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.CREATE_X_USER_PERMISSION + "\")")
    public VXUserPermission createXUserPermission(VXUserPermission vXUserPermission) {
        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();

        return xUserMgr.createXUserPermission(vXUserPermission);
    }

    @GET
    @Path("/permission/user/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_USER_PERMISSION + "\")")
    public VXUserPermission getXUserPermission(@PathParam("id") Long id) {
        return xUserMgr.getXUserPermission(id);
    }

    @PUT
    @Path("/permission/user/{id}")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.UPDATE_X_USER_PERMISSION + "\")")
    public VXUserPermission updateXUserPermission(VXUserPermission vXUserPermission) {
        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();

        return xUserMgr.updateXUserPermission(vXUserPermission);
    }

    @DELETE
    @Path("/permission/user/{id}")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.DELETE_X_USER_PERMISSION + "\")")
    public void deleteXUserPermission(@PathParam("id") Long id, @Context HttpServletRequest request) {
        boolean force = true;

        xUserMgr.checkAdminAccess();
        xUserMgr.deleteXUserPermission(id, force);
    }

    @GET
    @Path("/permission/user")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_USER_PERMISSION + "\")")
    public VXUserPermissionList searchXUserPermission(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xUserPermissionService.sortFields);

        searchUtil.extractString(request, searchCriteria, "id", "id", StringUtil.VALIDATION_NAME);
        searchUtil.extractString(request, searchCriteria, "userPermissionList", "userId", StringUtil.VALIDATION_NAME);

        return xUserMgr.searchXUserPermission(searchCriteria);
    }

    @GET
    @Path("/permission/user/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_USER_PERMISSION + "\")")
    public VXLong countXUserPermission(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xUserPermissionService.sortFields);

        return xUserMgr.getXUserPermissionSearchCount(searchCriteria);
    }

    // Handle group permissions
    @POST
    @Path("/permission/group")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.CREATE_X_GROUP_PERMISSION + "\")")
    public VXGroupPermission createXGroupPermission(VXGroupPermission vXGroupPermission) {
        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();

        return xUserMgr.createXGroupPermission(vXGroupPermission);
    }

    @GET
    @Path("/permission/group/{id}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_X_GROUP_PERMISSION + "\")")
    public VXGroupPermission getXGroupPermission(@PathParam("id") Long id) {
        return xUserMgr.getXGroupPermission(id);
    }

    @PUT
    @Path("/permission/group/{id}")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.UPDATE_X_GROUP_PERMISSION + "\")")
    public VXGroupPermission updateXGroupPermission(@PathParam("id") Long id, VXGroupPermission vXGroupPermission) {
        // if VXGroupPermission.id is specified, it should be same as the param 'id'
        if (vXGroupPermission.getId() == null) {
            vXGroupPermission.setId(id);
        } else if (!vXGroupPermission.getId().equals(id)) {
            throw restErrorUtil.createRESTException(HttpServletResponse.SC_BAD_REQUEST, "vXGroupPermission Id mismatch", true);
        }

        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();

        return xUserMgr.updateXGroupPermission(vXGroupPermission);
    }

    @DELETE
    @Path("/permission/group/{id}")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.DELETE_X_GROUP_PERMISSION + "\")")
    public void deleteXGroupPermission(@PathParam("id") Long id, @Context HttpServletRequest request) {
        boolean force = true;

        xUserMgr.checkAdminAccess();
        bizUtil.blockAuditorRoleUser();
        xUserMgr.deleteXGroupPermission(id, force);
    }

    @GET
    @Path("/permission/group")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SEARCH_X_GROUP_PERMISSION + "\")")
    public VXGroupPermissionList searchXGroupPermission(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupPermissionService.sortFields);

        searchUtil.extractString(request, searchCriteria, "id", "id", StringUtil.VALIDATION_NAME);
        searchUtil.extractString(request, searchCriteria, "groupPermissionList", "groupId", StringUtil.VALIDATION_NAME);

        return xUserMgr.searchXGroupPermission(searchCriteria);
    }

    @GET
    @Path("/permission/group/count")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.COUNT_X_GROUP_PERMISSION + "\")")
    public VXLong countXGroupPermission(@Context HttpServletRequest request) {
        SearchCriteria searchCriteria = searchUtil.extractCommonCriterias(request, xGroupPermissionService.sortFields);

        return xUserMgr.getXGroupPermissionSearchCount(searchCriteria);
    }

    @PUT
    @Path("/secure/users/activestatus")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.MODIFY_USER_ACTIVE_STATUS + "\")")
    public void modifyUserActiveStatus(HashMap<Long, Integer> statusMap) {
        xUserMgr.modifyUserActiveStatus(statusMap);
    }

    @PUT
    @Path("/secure/users/roles/{userId}")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SET_USER_ROLES_BY_ID + "\")")
    public VXStringList setUserRolesByExternalID(@PathParam("userId") Long userId, VXStringList roleList) {
        return xUserMgr.setUserRolesByExternalID(userId, roleList.getVXStrings());
    }

    @PUT
    @Path("/secure/users/roles/userName/{userName}")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.SET_USER_ROLES_BY_NAME + "\")")
    public VXStringList setUserRolesByName(@PathParam("userName") String userName, VXStringList roleList) {
        return xUserMgr.setUserRolesByName(userName, roleList.getVXStrings());
    }

    @GET
    @Path("/secure/users/external/{userId}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_USER_ROLES_BY_ID + "\")")
    public VXStringList getUserRolesByExternalID(@PathParam("userId") Long userId) {
        return xUserMgr.getUserRolesByExternalID(userId);
    }

    @GET
    @Path("/secure/users/roles/userName/{userName}")
    @Produces("application/json")
    @PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_USER_ROLES_BY_NAME + "\")")
    public VXStringList getUserRolesByName(@PathParam("userName") String userName) {
        return xUserMgr.getUserRolesByName(userName);
    }

    @DELETE
    @Path("/secure/users/delete")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteUsersByUserName(@Context HttpServletRequest request, VXStringList userList) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = StringUtils.isNotEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr);

        if (userList != null && userList.getList() != null) {
            for (VXString userName : userList.getList()) {
                if (StringUtils.isNotEmpty(userName.getValue())) {
                    VXUser vxUser = xUserService.getXUserByUserName(userName.getValue());

                    xUserMgr.deleteXUser(vxUser.getId(), forceDelete);
                }
            }
        }
    }

    @DELETE
    @Path("/secure/groups/delete")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteGroupsByGroupName(@Context HttpServletRequest request, VXStringList groupList) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = StringUtils.isNotEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr);

        if (groupList != null && groupList.getList() != null) {
            for (VXString groupName : groupList.getList()) {
                if (StringUtils.isNotEmpty(groupName.getValue())) {
                    VXGroup vxGroup = xGroupService.getGroupByGroupName(groupName.getValue());

                    xUserMgr.deleteXGroup(vxGroup.getId(), forceDelete);
                }
            }
        }
    }

    @DELETE
    @Path("/secure/users/{userName}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteSingleUserByUserName(@Context HttpServletRequest request, @PathParam("userName") String userName) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = StringUtils.isNotEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr);

        if (StringUtils.isNotEmpty(userName)) {
            VXUser vxUser = xUserService.getXUserByUserName(userName);

            xUserMgr.deleteXUser(vxUser.getId(), forceDelete);
        }
    }

    @DELETE
    @Path("/secure/groups/{groupName}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteSingleGroupByGroupName(@Context HttpServletRequest request, @PathParam("groupName") String groupName) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = StringUtils.isNotEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr);

        if (StringUtils.isNotEmpty(groupName)) {
            VXGroup vxGroup = xGroupService.getGroupByGroupName(groupName.trim());

            xUserMgr.deleteXGroup(vxGroup.getId(), forceDelete);
        }
    }

    @DELETE
    @Path("/secure/users/id/{userId}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteSingleUserByUserId(@Context HttpServletRequest request, @PathParam("userId") Long userId) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = StringUtils.isNotEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr);

        if (userId != null) {
            xUserMgr.deleteXUser(userId, forceDelete);
        }
    }

    @DELETE
    @Path("/secure/groups/id/{groupId}")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public void deleteSingleGroupByGroupId(@Context HttpServletRequest request, @PathParam("groupId") Long groupId) {
        String  forceDeleteStr = request.getParameter("forceDelete");
        boolean forceDelete    = StringUtils.isNotEmpty(forceDeleteStr) && "true".equalsIgnoreCase(forceDeleteStr);

        if (groupId != null) {
            xUserMgr.deleteXGroup(groupId, forceDelete);
        }
    }

    @GET
    @Path("/download/{serviceName}")
    @Produces("application/json")
    public RangerUserStore getRangerUserStoreIfUpdated(@PathParam("serviceName") String serviceName, @DefaultValue("-1") @QueryParam("lastKnownUserStoreVersion") Long lastKnownUserStoreVersion, @DefaultValue("0") @QueryParam("lastActivationTime") Long lastActivationTime, @QueryParam("pluginId") String pluginId, @DefaultValue("") @QueryParam("clusterName") String clusterName, @DefaultValue("") @QueryParam(RangerRESTUtils.REST_PARAM_CAPABILITIES) String pluginCapabilities, @Context HttpServletRequest request) {
        logger.debug("==> XUserREST.getRangerUserStoreIfUpdated(serviceName={}, lastKnownUserStoreVersion={}, lastActivationTime={})", serviceName, lastKnownUserStoreVersion, lastActivationTime);

        RangerUserStore ret               = null;
        boolean         isValid           = false;
        int             httpCode          = HttpServletResponse.SC_OK;
        String          logMsg            = null;
        Long            downloadedVersion = null;

        try {
            bizUtil.failUnauthenticatedDownloadIfNotAllowed();

            isValid = serviceUtil.isValidService(serviceName, request);
        } catch (WebApplicationException webException) {
            httpCode = webException.getResponse().getStatus();
            logMsg   = webException.getResponse().getEntity().toString();
        } catch (Exception e) {
            httpCode = HttpServletResponse.SC_BAD_REQUEST;
            logMsg   = e.getMessage();
        }

        if (isValid) {
            try {
                XXService xService = rangerDaoManager.getXXService().findByName(serviceName);

                if (xService != null) {
                    RangerUserStore rangerUserStore = xUserMgr.getRangerUserStoreIfUpdated(lastKnownUserStoreVersion);

                    if (rangerUserStore == null) {
                        downloadedVersion = lastKnownUserStoreVersion;
                        httpCode          = HttpServletResponse.SC_NOT_MODIFIED;
                        logMsg            = "No change since last update";
                    } else {
                        downloadedVersion = rangerUserStore.getUserStoreVersion();
                        ret               = rangerUserStore;
                        logMsg            = "Returning RangerUserStore version " + downloadedVersion;
                    }
                }
            } catch (Throwable excp) {
                logger.error("getRangerUserStoreIfUpdated(serviceName={}, lastKnownUserStoreVersion={}, lastActivationTime={}) failed", serviceName, lastKnownUserStoreVersion, lastActivationTime, excp);

                httpCode = HttpServletResponse.SC_BAD_REQUEST;
                logMsg   = excp.getMessage();
            }
        }

        assetMgr.createPluginInfo(serviceName, pluginId, request, RangerPluginInfo.ENTITY_TYPE_USERSTORE, downloadedVersion, lastKnownUserStoreVersion, lastActivationTime, httpCode, clusterName, pluginCapabilities);

        if (httpCode != HttpServletResponse.SC_OK) {
            boolean logError = httpCode != HttpServletResponse.SC_NOT_MODIFIED;

            throw restErrorUtil.createRESTException(httpCode, logMsg, logError);
        }

        logger.debug("<== XUserREST.getRangerUserStoreIfUpdated(serviceName={}, lastKnownUserStoreVersion={}, lastActivationTime={}): {}", serviceName, lastKnownUserStoreVersion, lastActivationTime, ret);

        return ret;
    }

    @GET
    @Path("/secure/download/{serviceName}")
    @Produces("application/json")
    public RangerUserStore getSecureRangerUserStoreIfUpdated(@PathParam("serviceName") String serviceName, @DefaultValue("-1") @QueryParam("lastKnownUserStoreVersion") Long lastKnownUserStoreVersion, @DefaultValue("0") @QueryParam("lastActivationTime") Long lastActivationTime, @QueryParam("pluginId") String pluginId, @DefaultValue("") @QueryParam("clusterName") String clusterName, @DefaultValue("") @QueryParam(RangerRESTUtils.REST_PARAM_CAPABILITIES) String pluginCapabilities, @Context HttpServletRequest request) {
        logger.debug("==> XUserREST.getSecureRangerUserStoreIfUpdated({}, {}, {})", serviceName, lastKnownUserStoreVersion, lastActivationTime);

        RangerUserStore ret               = null;
        int             httpCode          = HttpServletResponse.SC_OK;
        String          logMsg            = null;
        boolean         isAdmin           = bizUtil.isAdmin();
        boolean         isKeyAdmin        = bizUtil.isKeyAdmin();
        Long            downloadedVersion = null;
        boolean         isValid           = false;
        boolean         isAllowed;

        try {
            isValid = serviceUtil.isValidService(serviceName, request);
        } catch (WebApplicationException webException) {
            httpCode = webException.getResponse().getStatus();
            logMsg = webException.getResponse().getEntity().toString();
        } catch (Exception e) {
            httpCode = HttpServletResponse.SC_BAD_REQUEST;
            logMsg = e.getMessage();
        }

        try {
            XXService xService = rangerDaoManager.getXXService().findByName(serviceName);

            if (xService != null) {
                isValid = true;
            }

            if (isValid && xService != null) {
                XXServiceDef  xServiceDef   = rangerDaoManager.getXXServiceDef().getById(xService.getType());
                RangerService rangerService = svcStore.getServiceByName(serviceName);

                if (StringUtils.equals(xServiceDef.getImplclassname(), EmbeddedServiceDefsUtil.KMS_IMPL_CLASS_NAME)) {
                    if (isKeyAdmin) {
                        isAllowed = true;
                    } else {
                        isAllowed = bizUtil.isUserAllowed(rangerService, USERSTORE_DOWNLOAD_USERS);
                    }
                } else {
                    if (isAdmin) {
                        isAllowed = true;
                    } else {
                        isAllowed = bizUtil.isUserAllowed(rangerService, USERSTORE_DOWNLOAD_USERS);
                    }
                }

                if (isAllowed) {
                    RangerUserStore rangerUserStore = xUserMgr.getRangerUserStoreIfUpdated(lastKnownUserStoreVersion);

                    if (rangerUserStore == null) {
                        downloadedVersion = lastKnownUserStoreVersion;
                        httpCode          = HttpServletResponse.SC_NOT_MODIFIED;
                        logMsg            = "No change since last update";
                    } else {
                        downloadedVersion = rangerUserStore.getUserStoreVersion();
                        ret               = rangerUserStore;
                        logMsg            = "Returning RangerUserStore =>" + (ret);
                    }
                } else {
                    logger.error("getSecureRangerUserStoreIfUpdated({}, {}) failed as User doesn't have permission to download UsersAndGroups", serviceName, lastKnownUserStoreVersion);

                    httpCode = HttpServletResponse.SC_FORBIDDEN; // assert user is authenticated.
                    logMsg   = "User doesn't have permission to download UsersAndGroups";
                }
            }
        } catch (Throwable excp) {
            logger.error("getSecureRangerUserStoreIfUpdated({}, {}, {}) failed", serviceName, lastKnownUserStoreVersion, lastActivationTime, excp);

            httpCode = HttpServletResponse.SC_BAD_REQUEST;
            logMsg   = excp.getMessage();
        }

        assetMgr.createPluginInfo(serviceName, pluginId, request, RangerPluginInfo.ENTITY_TYPE_USERSTORE, downloadedVersion, lastKnownUserStoreVersion, lastActivationTime, httpCode, clusterName, pluginCapabilities);

        if (httpCode != HttpServletResponse.SC_OK) {
            boolean logError = httpCode != HttpServletResponse.SC_NOT_MODIFIED;

            throw restErrorUtil.createRESTException(httpCode, logMsg, logError);
        }

        logger.debug("<== XUserREST.getSecureRangerUserStoreIfUpdated({}, {}, {}): {}", serviceName, lastKnownUserStoreVersion, lastActivationTime, ret);

        return ret;
    }

    @POST
    @Path("/ugsync/auditinfo")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public VXUgsyncAuditInfo postUserGroupAuditInfo(VXUgsyncAuditInfo vxUgsyncAuditInfo) {
        return xUserMgr.postUserGroupAuditInfo(vxUgsyncAuditInfo);
    }

    @GET
    @Path("/ugsync/groupusers")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public Map<String, Set<String>> getAllGroupUsers() {
        return rangerDaoManager.getXXGroupUser().findUsersByGroupIds();
    }

    @POST
    @Path("/ugsync/users")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    @Transactional(readOnly = false, propagation = Propagation.NOT_SUPPORTED)
    public String addOrUpdateUsers(VXUserList users) {
        int ret = xUserMgr.createOrUpdateXUsers(users);

        return String.valueOf(ret);
    }

    @POST
    @Path("/ugsync/groups")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public int addOrUpdateGroups(VXGroupList groups) {
        return xUserMgr.createOrUpdateXGroups(groups);
    }

    @POST
    @Path("/ugsync/groupusers")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public int addOrUpdateGroupUsersList(List<GroupUserInfo> groupUserInfoList) {
        return xUserMgr.createOrDeleteXGroupUserList(groupUserInfoList);
    }

    @POST
    @Path("/users/roleassignments")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public List<String> setXUserRolesByName(UsersGroupRoleAssignments ugRoleAssignments) {
        return xUserMgr.updateUserRoleAssignments(ugRoleAssignments);
    }

    @POST
    @Path("/ugsync/groups/visibility")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public int updateDeletedGroups(Set<String> deletedGroups) {
        return xUserMgr.updateDeletedGroups(deletedGroups);
    }

    @POST
    @Path("/ugsync/users/visibility")
    @Consumes("application/json")
    @Produces("application/json")
    @PreAuthorize("hasRole('ROLE_SYS_ADMIN')")
    public int updateDeletedUsers(Set<String> deletedUsers) {
        return xUserMgr.updateDeletedUsers(deletedUsers);
    }
}
