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

package org.apache.ranger.plugin.service;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.admin.client.RangerAdminClient;
import org.apache.ranger.admin.client.RangerAdminRESTClient;
import org.apache.ranger.audit.provider.AuditHandler;
import org.apache.ranger.audit.provider.AuditProviderFactory;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.audit.provider.StandAloneAuditProviderFactory;
import org.apache.ranger.authorization.hadoop.config.RangerAuditConfig;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.authorization.utils.StringUtil;
import org.apache.ranger.plugin.contextenricher.RangerAdminGdsInfoRetriever;
import org.apache.ranger.plugin.contextenricher.RangerAdminUserStoreRetriever;
import org.apache.ranger.plugin.contextenricher.RangerContextEnricher;
import org.apache.ranger.plugin.contextenricher.RangerGdsEnricher;
import org.apache.ranger.plugin.contextenricher.RangerTagEnricher;
import org.apache.ranger.plugin.contextenricher.RangerUserStoreEnricher;
import org.apache.ranger.plugin.model.RangerBaseModelObject;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerRole;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineImpl;
import org.apache.ranger.plugin.policyengine.RangerRequestScriptEvaluator;
import org.apache.ranger.plugin.policyengine.RangerResourceACLs;
import org.apache.ranger.plugin.policyengine.RangerResourceAccessInfo;
import org.apache.ranger.plugin.policyengine.gds.GdsPolicyEngine;
import org.apache.ranger.plugin.policyevaluator.RangerPolicyEvaluator;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.plugin.util.DownloadTrigger;
import org.apache.ranger.plugin.util.GrantRevokeRequest;
import org.apache.ranger.plugin.util.GrantRevokeRoleRequest;
import org.apache.ranger.plugin.util.PerfDataRecorder;
import org.apache.ranger.plugin.util.PolicyRefresher;
import org.apache.ranger.plugin.util.RangerPolicyDeltaUtil;
import org.apache.ranger.plugin.util.RangerRoles;
import org.apache.ranger.plugin.util.RangerRolesUtil;
import org.apache.ranger.plugin.util.RangerUserStore;
import org.apache.ranger.plugin.util.ServiceDefUtil;
import org.apache.ranger.plugin.util.ServiceGdsInfo;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.apache.ranger.plugin.util.ServiceTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class RangerBasePlugin {
    private static final Logger LOG = LoggerFactory.getLogger(RangerBasePlugin.class);

    private final RangerPluginConfig          pluginConfig;
    private final RangerPluginContext         pluginContext;
    private final Map<String, LogHistory>     logHistoryList = new Hashtable<>();
    private final int                         logInterval    = 30000; // 30 seconds
    private final DownloadTrigger             accessTrigger  = new DownloadTrigger();
    private final List<RangerChainedPlugin>   chainedPlugins;
    private final boolean                     dedupStrings;
    private       PolicyRefresher             refresher;
    private       RangerPolicyEngine          policyEngine;
    private       RangerAuthContext           currentAuthContext;
    private       RangerAccessResultProcessor resultProcessor;
    private       RangerRoles                 roles;
    private       boolean                     isUserStoreEnricherAddedImplcitly;
    private       Map<String, String>         serviceConfigs;

    public RangerBasePlugin(String serviceType, String appId) {
        this(new RangerPluginConfig(serviceType, null, appId, null, null, null));
    }

    public RangerBasePlugin(String serviceType, String serviceName, String appId) {
        this(new RangerPluginConfig(serviceType, serviceName, appId, null, null, null));
    }

    public RangerBasePlugin(RangerPluginConfig pluginConfig) {
        this.pluginConfig  = pluginConfig;
        this.pluginContext = new RangerPluginContext(pluginConfig);

        boolean usePerfDataRecorder  = pluginConfig.getBoolean("ranger.perf.aggregate.data", false);
        int     perfDataDumpInterval = pluginConfig.getInt("ranger.perf.aggregate.data.dump.interval", 0);
        boolean usePerfDataLock      = pluginConfig.getBoolean("ranger.perf.aggregate.data.lock.enabled", false);
        String  nullSafeSupplier     = pluginConfig.get(pluginConfig.getPropertyPrefix() + ".null_safe.supplier", RangerBaseModelObject.NULL_SAFE_SUPPLIER_V2);

        LOG.info("{}.null_safe.supplier={}", pluginConfig.getPropertyPrefix(), nullSafeSupplier);

        RangerBaseModelObject.setNullSafeSupplier(nullSafeSupplier);

        PerfDataRecorder.initialize(usePerfDataRecorder, perfDataDumpInterval, usePerfDataLock, null);

        Set<String> superUsers         = toSet(pluginConfig.get(pluginConfig.getPropertyPrefix() + ".super.users"));
        Set<String> superGroups        = toSet(pluginConfig.get(pluginConfig.getPropertyPrefix() + ".super.groups"));
        Set<String> auditExcludeUsers  = toSet(pluginConfig.get(pluginConfig.getPropertyPrefix() + ".audit.exclude.users"));
        Set<String> auditExcludeGroups = toSet(pluginConfig.get(pluginConfig.getPropertyPrefix() + ".audit.exclude.groups"));
        Set<String> auditExcludeRoles  = toSet(pluginConfig.get(pluginConfig.getPropertyPrefix() + ".audit.exclude.roles"));
        Set<String> serviceAdmins      = toSet(pluginConfig.get(pluginConfig.getPropertyPrefix() + ".service.admins"));

        setSuperUsersAndGroups(superUsers, superGroups);
        setAuditExcludedUsersGroupsRoles(auditExcludeUsers, auditExcludeGroups, auditExcludeRoles);
        setIsFallbackSupported(pluginConfig.getBoolean(pluginConfig.getPropertyPrefix() + ".is.fallback.supported", false));
        setServiceAdmins(serviceAdmins);

        String  ugiPrefix = pluginConfig.getPropertyPrefix() + ".ugi";
        boolean initUgi   = pluginConfig.getBoolean(ugiPrefix + ".initialize", false);

        if (initUgi) {
            String ugiLoginType = pluginConfig.get(ugiPrefix + ".login.type");

            if (StringUtils.equalsIgnoreCase(ugiLoginType, "keytab")) {
                String principal = pluginConfig.get(ugiPrefix + ".keytab.principal");
                String keytab    = pluginConfig.get(ugiPrefix + ".keytab.file");

                if (StringUtils.isNotBlank(principal) && StringUtils.isNotBlank(keytab)) {
                    LOG.info("UGI login: principal={}, keytab={}", principal, keytab);

                    try {
                        UserGroupInformation.loginUserFromKeytab(principal, keytab);
                    } catch (IOException excp) {
                        LOG.error("UGI login: failed", excp);

                        throw new RuntimeException(excp);
                    }
                } else {
                    String msg = String.format("UGI login: invalid configuration: %s=%s, %s=%s", ugiPrefix + ".keytab.principal", principal, ugiPrefix + ".keytab.file", keytab);

                    LOG.error(msg);

                    throw new RuntimeException(msg);
                }
            } else if (StringUtils.equalsIgnoreCase(ugiLoginType, "jaas")) {
                String jaasAppConfig = pluginConfig.get(ugiPrefix + ".jaas.appconfig");

                if (StringUtils.isNotBlank(jaasAppConfig)) {
                    LOG.info("UGI login: jaasAppConfig={}", jaasAppConfig);

                    try {
                        MiscUtil.setUGIFromJAASConfig(jaasAppConfig);
                    } catch (Exception excp) {
                        LOG.error("UGI login: jaasAppConfig={} failed", jaasAppConfig, excp);

                        throw new RuntimeException(excp);
                    }
                } else {
                    String msg = String.format("UGI login: invalid configuration: %s=%s", ugiPrefix + ".jaas.appconfig", jaasAppConfig);

                    LOG.error(msg);

                    throw new RuntimeException(msg);
                }
            } else {
                LOG.warn("UGI login: invalid configuration {}={}", ugiPrefix + ".login.type", ugiLoginType);
            }
        }

        RangerRequestScriptEvaluator.init(pluginConfig);

        this.dedupStrings   = pluginConfig.getBoolean(pluginConfig.getPropertyPrefix() + ".dedup.strings", true);
        this.chainedPlugins = initChainedPlugins();
    }

    public RangerBasePlugin(RangerPluginConfig pluginConfig, ServicePolicies policies, ServiceTags tags, RangerRoles roles) {
        this(pluginConfig, policies, tags, roles, null, null);
    }

    public RangerBasePlugin(RangerPluginConfig pluginConfig, ServicePolicies policies, ServiceTags tags, RangerRoles roles, RangerUserStore userStore) {
        this(pluginConfig, policies, tags, roles, userStore, null);
    }

    public RangerBasePlugin(RangerPluginConfig pluginConfig, ServicePolicies policies, ServiceTags tags, RangerRoles roles, RangerUserStore userStore, ServiceGdsInfo gdsInfo) {
        this(pluginConfig);

        init();
        setPolicies(policies);
        setRoles(roles);

        if (tags != null) {
            RangerTagEnricher tagEnricher = getTagEnricher();

            if (tagEnricher != null) {
                tagEnricher.setServiceTags(tags);
            } else {
                LOG.warn("RangerBasePlugin(tagsVersion={}): no tag enricher found. Plugin will not enforce tag-based policies", tags.getTagVersion());
            }
        }

        if (userStore != null) {
            RangerUserStoreEnricher userStoreEnricher = getUserStoreEnricher();

            if (userStoreEnricher != null) {
                userStoreEnricher.setRangerUserStore(userStore);
            } else {
                LOG.warn("RangerBasePlugin(userStoreVersion={}): no userstore enricher found. Plugin will not enforce user/group attribute-based policies", userStore.getUserStoreVersion());
            }
        }

        if (gdsInfo != null) {
            RangerGdsEnricher gdsEnricher = getGdsEnricher();

            if (gdsEnricher != null) {
                gdsEnricher.setGdsInfo(gdsInfo);
            } else {
                LOG.warn("RangerBasePlugin(gdsInfo={}): no GDS enricher found. Plugin will not enforce GDS policies", gdsInfo.getGdsVersion());
            }
        }
    }

    public static AuditHandler getAuditProvider(String serviceName) {
        AuditProviderFactory providerFactory = RangerBasePlugin.getAuditProviderFactory(serviceName);

        return providerFactory.getAuditProvider();
    }

    public static RangerAdminClient createAdminClient(RangerPluginConfig pluginConfig) {
        LOG.debug("==> RangerBasePlugin.createAdminClient({}, {}, {})", pluginConfig.getServiceName(), pluginConfig.getAppId(), pluginConfig.getPropertyPrefix());

        RangerAdminClient ret              = null;
        String            propertyName     = pluginConfig.getPropertyPrefix() + ".policy.source.impl";
        String            policySourceImpl = pluginConfig.get(propertyName);

        if (StringUtils.isEmpty(policySourceImpl)) {
            LOG.debug("Value for property[{}] was null or empty. Unexpected! Will use policy source of type[{}]", propertyName, RangerAdminRESTClient.class.getName());
        } else {
            LOG.debug("Value for property[{}] was [{}].", propertyName, policySourceImpl);

            try {
                @SuppressWarnings("unchecked")
                Class<RangerAdminClient> adminClass = (Class<RangerAdminClient>) Class.forName(policySourceImpl);

                ret = adminClass.newInstance();
            } catch (Exception excp) {
                LOG.error("failed to instantiate policy source of type '{}'. Will use policy source of type '{}'", policySourceImpl, RangerAdminRESTClient.class.getName(), excp);
            }
        }

        if (ret == null) {
            ret = new RangerAdminRESTClient();
        }

        ret.init(pluginConfig.getServiceName(), pluginConfig.getAppId(), pluginConfig.getPropertyPrefix(), pluginConfig);

        LOG.debug("<== RangerBasePlugin.createAdminClient({}, {}, {}): policySourceImpl={}, client={}", pluginConfig.getServiceName(), pluginConfig.getAppId(), pluginConfig.getPropertyPrefix(), policySourceImpl, ret);

        return ret;
    }

    public static RangerResourceACLs getMergedResourceACLs(RangerResourceACLs baseACLs, RangerResourceACLs chainedACLs) {
        LOG.debug("==> RangerBasePlugin.getMergedResourceACLs()");
        LOG.debug("baseACLs:[{}]", baseACLs);
        LOG.debug("chainedACLS:[{}]", chainedACLs);

        overrideACLs(chainedACLs, baseACLs, RangerRolesUtil.ROLES_FOR.USER);
        overrideACLs(chainedACLs, baseACLs, RangerRolesUtil.ROLES_FOR.GROUP);
        overrideACLs(chainedACLs, baseACLs, RangerRolesUtil.ROLES_FOR.ROLE);
        baseACLs.getDatasets().addAll(chainedACLs.getDatasets());
        baseACLs.getProjects().addAll(chainedACLs.getProjects());

        LOG.debug("<== RangerBasePlugin.getMergedResourceACLs() : ret:[{}]", baseACLs);

        return baseACLs;
    }

    public String getServiceType() {
        return pluginConfig.getServiceType();
    }

    public String getAppId() {
        return pluginConfig.getAppId();
    }

    public RangerPluginConfig getConfig() {
        return pluginConfig;
    }

    public String getClusterName() {
        return pluginConfig.getClusterName();
    }

    public RangerPluginContext getPluginContext() {
        return pluginContext;
    }

    public RangerAuthContext getCurrentRangerAuthContext() {
        return currentAuthContext;
    }

    public List<RangerChainedPlugin> getChainedPlugins() {
        return chainedPlugins;
    }

    // For backward compatibility
    public RangerAuthContext createRangerAuthContext() {
        return currentAuthContext;
    }

    public RangerRoles getRoles() {
        return this.roles;
    }

    public void setRoles(RangerRoles roles) {
        this.roles = roles;

        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            policyEngine.setRoles(roles);
        }

        pluginContext.notifyAuthContextChanged();
    }

    public void setAuditExcludedUsersGroupsRoles(Set<String> users, Set<String> groups, Set<String> roles) {
        pluginConfig.setAuditExcludedUsersGroupsRoles(users, groups, roles);
    }

    public void setSuperUsersAndGroups(Set<String> users, Set<String> groups) {
        pluginConfig.setSuperUsersGroups(users, groups);
    }

    public void setIsFallbackSupported(boolean isFallbackSupported) {
        pluginConfig.setIsFallbackSupported(isFallbackSupported);
    }

    public void setServiceAdmins(Set<String> users) {
        pluginConfig.setServiceAdmins(users);
    }

    public RangerServiceDef getServiceDef() {
        RangerPolicyEngine policyEngine = this.policyEngine;

        return policyEngine != null ? policyEngine.getServiceDef() : null;
    }

    public int getServiceDefId() {
        RangerServiceDef serviceDef = getServiceDef();

        return serviceDef != null && serviceDef.getId() != null ? serviceDef.getId().intValue() : -1;
    }

    public String getServiceName() {
        return pluginConfig.getServiceName();
    }

    public AuditProviderFactory getAuditProviderFactory() {
        return RangerBasePlugin.getAuditProviderFactory(getServiceName());
    }

    public void init() {
        cleanup();

        AuditProviderFactory providerFactory = AuditProviderFactory.getInstance();

        if (!providerFactory.isInitDone()) {
            if (pluginConfig.getProperties() != null) {
                providerFactory.init(pluginConfig.getProperties(), getAppId());
            } else {
                LOG.error("Audit subsystem is not initialized correctly. Please check audit configuration. ");
                LOG.error("No authorization audits will be generated. ");
            }
        }

        if (!pluginConfig.getPolicyEngineOptions().disablePolicyRefresher) {
            refresher = new PolicyRefresher(this);

            LOG.info("Created PolicyRefresher Thread({})", refresher.getName());

            refresher.setDaemon(true);
            refresher.startRefresher();
        }

        for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
            chainedPlugin.init();
        }
    }

    public long getPoliciesVersion() {
        RangerPolicyEngine policyEngine = this.policyEngine;
        Long               ret          = policyEngine != null ? policyEngine.getPolicyVersion() : null;

        return ret != null ? ret : -1L;
    }

    public long getTagsVersion() {
        RangerTagEnricher tagEnricher = getTagEnricher();
        Long              ret         = tagEnricher != null ? tagEnricher.getServiceTagsVersion() : null;

        return ret != null ? ret : -1L;
    }

    public long getRolesVersion() {
        RangerPolicyEngine policyEngine = this.policyEngine;
        Long               ret          = policyEngine != null ? policyEngine.getRoleVersion() : null;

        return ret != null ? ret : -1L;
    }

    public long getUserStoreVersion() {
        RangerUserStoreEnricher userStoreEnricher = getUserStoreEnricher();
        Long                    ret               = userStoreEnricher != null ? userStoreEnricher.getUserStoreVersion() : null;

        return ret != null ? ret : -1L;
    }

    public void setPolicies(ServicePolicies policies) {
        LOG.debug("==> setPolicies({})", policies);

        if (pluginConfig.isEnableImplicitUserStoreEnricher() && policies != null && !ServiceDefUtil.isUserStoreEnricherPresent(policies)) {
            String retrieverClassName = pluginConfig.get(RangerUserStoreEnricher.USERSTORE_RETRIEVER_CLASSNAME_OPTION, RangerAdminUserStoreRetriever.class.getCanonicalName());
            String retrieverPollIntMs = pluginConfig.get(RangerUserStoreEnricher.USERSTORE_REFRESHER_POLLINGINTERVAL_OPTION, Integer.toString(60 * 1000));

            // in case of delta, policies will only have changes; hence add userStoreEnricher if it was implicitly added previous calls to setPolicies()
            if (RangerPolicyDeltaUtil.hasPolicyDeltas(policies) == Boolean.TRUE && isUserStoreEnricherAddedImplcitly) {
                ServiceDefUtil.addUserStoreEnricher(policies, retrieverClassName, retrieverPollIntMs);
            } else if (pluginConfig.isUseRangerGroups() || pluginConfig.isConvertEmailToUsername()) {
                isUserStoreEnricherAddedImplcitly = ServiceDefUtil.addUserStoreEnricher(policies, retrieverClassName, retrieverPollIntMs);
            } else {
                isUserStoreEnricherAddedImplcitly = ServiceDefUtil.addUserStoreEnricherIfNeeded(policies, retrieverClassName, retrieverPollIntMs);
            }
        }

        if (pluginConfig.isEnableImplicitGdsInfoEnricher() && policies != null && !ServiceDefUtil.isGdsInfoEnricherPresent(policies)) {
            String retrieverClassName = pluginConfig.get(RangerGdsEnricher.RETRIEVER_CLASSNAME_OPTION, RangerAdminGdsInfoRetriever.class.getCanonicalName());
            String retrieverPollIntMs = pluginConfig.get(RangerGdsEnricher.REFRESHER_POLLINGINTERVAL_OPTION, Integer.toString(60 * 1000));

            ServiceDefUtil.addGdsInfoEnricher(policies, retrieverClassName, retrieverPollIntMs);
        }

        // guard against catastrophic failure during policy engine Initialization or
        try {
            RangerPolicyEngine oldPolicyEngine   = this.policyEngine;
            ServicePolicies    servicePolicies   = null;
            boolean            isNewEngineNeeded = true;
            boolean            usePolicyDeltas   = false;

            if (policies == null) {
                policies = getDefaultSvcPolicies();

                if (policies == null) {
                    LOG.error("Could not get default Service Policies. Keeping old policy-engine!");

                    isNewEngineNeeded = false;
                }
            } else {
                if (dedupStrings) {
                    policies.dedupStrings();
                }

                Boolean hasPolicyDeltas = RangerPolicyDeltaUtil.hasPolicyDeltas(policies);

                if (hasPolicyDeltas == null) {
                    LOG.info("Downloaded policies do not require policy change !! [{}]", policies);

                    if (this.policyEngine == null) {
                        LOG.info("There are no material changes, and current policy-engine is null! Creating a policy-engine with default service policies");

                        ServicePolicies defaultSvcPolicies = getDefaultSvcPolicies();

                        if (defaultSvcPolicies == null) {
                            LOG.error("Could not get default Service Policies. Keeping old policy-engine! This is a FATAL error as the old policy-engine is null!");

                            isNewEngineNeeded = false;
                        } else {
                            defaultSvcPolicies.setPolicyVersion(policies.getPolicyVersion());
                            policies          = defaultSvcPolicies;
                            isNewEngineNeeded = true;
                        }
                    } else {
                        LOG.info("Keeping old policy-engine!");

                        isNewEngineNeeded = false;
                    }
                } else {
                    if (hasPolicyDeltas.equals(Boolean.TRUE)) {
                        // Rebuild policies from deltas
                        RangerPolicyEngineImpl policyEngine = (RangerPolicyEngineImpl) oldPolicyEngine;

                        servicePolicies = ServicePolicies.applyDelta(policies, policyEngine);

                        if (servicePolicies != null) {
                            usePolicyDeltas = true;
                        } else {
                            LOG.error("Could not apply deltas={}", Arrays.toString(policies.getPolicyDeltas().toArray()));
                            LOG.warn("Keeping old policy-engine!");
                            isNewEngineNeeded = false;
                        }
                    } else {
                        if (policies.getPolicies() == null) {
                            policies.setPolicies(new ArrayList<>());
                        }
                        if (MapUtils.isNotEmpty(policies.getSecurityZones())) {
                            for (ServicePolicies.SecurityZoneInfo element : policies.getSecurityZones().values()) {
                                if (element.getPolicies() == null) {
                                    element.setPolicies(new ArrayList<>());
                                }
                            }
                        }
                    }
                }
            }

            if (isNewEngineNeeded) {
                RangerPolicyEngine newPolicyEngine      = null;
                boolean            isPolicyEngineShared = false;

                if (!usePolicyDeltas) {
                    LOG.debug("Creating engine from policies");

                    newPolicyEngine = new RangerPolicyEngineImpl(policies, pluginContext, roles);
                } else {
                    LOG.debug("policy-deltas are not null");

                    if (CollectionUtils.isNotEmpty(policies.getPolicyDeltas()) || MapUtils.isNotEmpty(policies.getSecurityZones())) {
                        LOG.debug("Non empty policy-deltas found. Cloning engine using policy-deltas");

                        if (oldPolicyEngine != null) {
                            RangerPolicyEngineImpl oldPolicyEngineImpl = (RangerPolicyEngineImpl) oldPolicyEngine;

                            newPolicyEngine = RangerPolicyEngineImpl.getPolicyEngine(oldPolicyEngineImpl, policies);
                        }

                        if (newPolicyEngine != null) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Applied policyDeltas={})", Arrays.toString(policies.getPolicyDeltas().toArray()));
                            }

                            isPolicyEngineShared = true;
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Failed to apply policyDeltas={}), Creating engine from policies", Arrays.toString(policies.getPolicyDeltas().toArray()));
                                LOG.debug("Creating new engine from servicePolicies:[{}]", servicePolicies);
                            }

                            newPolicyEngine = new RangerPolicyEngineImpl(servicePolicies, pluginContext, roles);
                        }
                    } else {
                        LOG.debug("Empty policy-deltas. No need to change policy engine");
                    }
                }

                if (newPolicyEngine != null) {
                    if (!isPolicyEngineShared) {
                        newPolicyEngine.setUseForwardedIPAddress(pluginConfig.isUseForwardedIPAddress());
                        newPolicyEngine.setTrustedProxyAddresses(pluginConfig.getTrustedProxyAddresses());
                    }

                    setServiceConfigs(policies.getServiceConfig());

                    LOG.info("Switching policy engine from [{}]", getPolicyVersion());
                    this.policyEngine = newPolicyEngine;
                    LOG.info("Switched policy engine to [{}]", getPolicyVersion());
                    this.currentAuthContext = pluginContext.getAuthContext();

                    pluginContext.notifyAuthContextChanged();

                    if (oldPolicyEngine != null && oldPolicyEngine != newPolicyEngine) {
                        ((RangerPolicyEngineImpl) oldPolicyEngine).releaseResources(!isPolicyEngineShared);
                    }

                    if (this.refresher != null) {
                        boolean doPreserveDeltas = pluginConfig.getBoolean(pluginConfig.getPropertyPrefix() + ".preserve.deltas", false);
                        if (!doPreserveDeltas) {
                            this.refresher.saveToCache(usePolicyDeltas ? servicePolicies : policies);
                        } else {
                            // Save both deltas and all policies to cache for verification
                            this.refresher.saveToCache(policies);

                            if (usePolicyDeltas) {
                                this.refresher.saveToCache(servicePolicies);
                            }
                        }
                    }
                }
            } else {
                LOG.warn("Leaving current policy engine as-is");
                LOG.warn("Policies are not saved to cache. policyVersion in the policy-cache may be different than in Ranger-admin, even though the policies are the same!");
                LOG.warn("Ranger-PolicyVersion:[{}], Cached-PolicyVersion:[{}]", policies != null ? policies.getPolicyVersion() : -1L, getPoliciesVersion());
            }
        } catch (Exception e) {
            LOG.error("setPolicies: policy engine initialization failed!  Leaving current policy engine as-is. Exception : ", e);
        }

        LOG.debug("<== setPolicies({})", policies);
    }

    public void cleanup() {
        PolicyRefresher refresher = this.refresher;
        this.refresher = null;

        RangerPolicyEngine policyEngine = this.policyEngine;
        this.policyEngine = null;

        if (refresher != null) {
            refresher.stopRefresher();
        }

        if (policyEngine != null) {
            ((RangerPolicyEngineImpl) policyEngine).releaseResources(true);
        }
    }

    public RangerAccessResultProcessor getResultProcessor() {
        return this.resultProcessor;
    }

    public void setResultProcessor(RangerAccessResultProcessor resultProcessor) {
        this.resultProcessor = resultProcessor;
    }

    public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
        return isAccessAllowed(request, resultProcessor);
    }

    public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests) {
        return isAccessAllowed(requests, resultProcessor);
    }

    public RangerAccessResult isAccessAllowed(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
        RangerAccessResult ret          = null;
        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            ret = policyEngine.evaluatePolicies(request, RangerPolicy.POLICY_TYPE_ACCESS, null);
        }

        if (ret != null) {
            for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
                LOG.debug("BasePlugin.isAccessAllowed result=[{}]", ret);
                LOG.debug("Calling chainedPlugin.isAccessAllowed for service:[{}]", chainedPlugin.plugin.pluginConfig.getServiceName());

                RangerAccessResult chainedResult;

                if (ret.getIsAccessDetermined() && chainedPlugin.skipAccessCheckIfAlreadyDetermined) {
                    chainedResult = null;
                } else {
                    chainedResult = chainedPlugin.isAccessAllowed(request);
                }

                if (chainedResult != null) {
                    LOG.debug("chainedPlugin.isAccessAllowed for service:[{}] returned result=[{}]", chainedPlugin.plugin.pluginConfig.getServiceName(), chainedResult);

                    updateResultFromChainedResult(ret, chainedResult);

                    LOG.debug("After updating result from chainedPlugin.isAccessAllowed for service:[{}], result={}]", chainedPlugin.plugin.pluginConfig.getServiceName(), ret);
                }
            }
        }

        if (policyEngine != null) {
            policyEngine.evaluateAuditPolicies(ret);
        }

        if (resultProcessor != null) {
            resultProcessor.processResult(ret);
        }
        return ret;
    }

    public Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests, RangerAccessResultProcessor resultProcessor) {
        Collection<RangerAccessResult> ret          = null;
        RangerPolicyEngine             policyEngine = this.policyEngine;

        if (policyEngine != null) {
            ret = policyEngine.evaluatePolicies(requests, RangerPolicy.POLICY_TYPE_ACCESS, null);
        }

        if (CollectionUtils.isNotEmpty(ret)) {
            for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
                Collection<RangerAccessResult> chainedResults = chainedPlugin.isAccessAllowed(requests);

                if (CollectionUtils.isNotEmpty(chainedResults)) {
                    Iterator<RangerAccessResult> iterRet            = ret.iterator();
                    Iterator<RangerAccessResult> iterChainedResults = chainedResults.iterator();

                    while (iterRet.hasNext() && iterChainedResults.hasNext()) {
                        RangerAccessResult result        = iterRet.next();
                        RangerAccessResult chainedResult = iterChainedResults.next();

                        if (result != null && chainedResult != null) {
                            updateResultFromChainedResult(result, chainedResult);
                        }
                    }
                }
            }
        }

        if (policyEngine != null && CollectionUtils.isNotEmpty(ret)) {
            for (RangerAccessResult result : ret) {
                policyEngine.evaluateAuditPolicies(result);
            }
        }

        if (resultProcessor != null) {
            resultProcessor.processResults(ret);
        }

        return ret;
    }

    public RangerAccessResult evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
        RangerPolicyEngine policyEngine = this.policyEngine;
        RangerAccessResult ret          = null;

        if (policyEngine != null) {
            ret = policyEngine.evaluatePolicies(request, RangerPolicy.POLICY_TYPE_DATAMASK, resultProcessor);

            if (ret != null) {
                for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
                    LOG.debug("BasePlugin.evalDataMaskPolicies result=[{}]", ret);
                    LOG.debug("Calling chainedPlugin.evalDataMaskPolicies for service:[{}]", chainedPlugin.plugin.pluginConfig.getServiceName());

                    RangerAccessResult chainedResult = chainedPlugin.evalDataMaskPolicies(request);

                    if (chainedResult != null) {
                        LOG.debug("chainedPlugin.evalDataMaskPolicies for service:[{}] returned result=[{}]", chainedPlugin.plugin.pluginConfig.getServiceName(), chainedResult);

                        updateResultFromChainedResult(ret, chainedResult);

                        LOG.debug("After updating result from chainedPlugin.evalDataMaskPolicies for service:[{}], result={}]", chainedPlugin.plugin.pluginConfig.getServiceName(), ret);
                    }
                }
            }

            policyEngine.evaluateAuditPolicies(ret);
        }

        return ret;
    }

    public RangerAccessResult evalRowFilterPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor) {
        RangerPolicyEngine policyEngine = this.policyEngine;
        RangerAccessResult ret          = null;

        if (policyEngine != null) {
            ret = policyEngine.evaluatePolicies(request, RangerPolicy.POLICY_TYPE_ROWFILTER, resultProcessor);

            if (ret != null) {
                for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
                    LOG.debug("BasePlugin.evalRowFilterPolicies result=[{}]", ret);
                    LOG.debug("Calling chainedPlugin.evalRowFilterPolicies for service:[{}]", chainedPlugin.plugin.pluginConfig.getServiceName());

                    RangerAccessResult chainedResult = chainedPlugin.evalRowFilterPolicies(request);

                    if (chainedResult != null) {
                        LOG.debug("chainedPlugin.evalRowFilterPolicies for service:[{}] returned result=[{}]", chainedPlugin.plugin.pluginConfig.getServiceName(), chainedResult);

                        updateResultFromChainedResult(ret, chainedResult);

                        LOG.debug("After updating result from chainedPlugin.evalRowFilterPolicies for service:[{}], result={}]", chainedPlugin.plugin.pluginConfig.getServiceName(), ret);
                    }
                }
            }

            policyEngine.evaluateAuditPolicies(ret);
        }

        return ret;
    }

    public void evalAuditPolicies(RangerAccessResult result) {
        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            policyEngine.evaluateAuditPolicies(result);
        }
    }

    public RangerResourceAccessInfo getResourceAccessInfo(RangerAccessRequest request) {
        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            return policyEngine.getResourceAccessInfo(request);
        }

        return null;
    }

    public RangerResourceACLs getResourceACLs(RangerAccessRequest request) {
        return getResourceACLs(request, null);
    }

    public RangerResourceACLs getResourceACLs(RangerAccessRequest request, Integer policyType) {
        RangerResourceACLs ret          = null;
        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            ret = policyEngine.getResourceACLs(request, policyType);
        }

        for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
            RangerResourceACLs chainedResourceACLs = chainedPlugin.getResourceACLs(request, policyType);

            if (chainedResourceACLs != null) {
                LOG.debug("Chained-plugin returned non-null ACLs!!");

                if (chainedPlugin.isAuthorizeOnlyWithChainedPlugin()) {
                    LOG.debug("Chained-plugin is configured to ignore Base-plugin's ACLs");

                    ret = chainedResourceACLs;
                    break;
                } else {
                    if (ret != null) {
                        ret = getMergedResourceACLs(ret, chainedResourceACLs);
                    }
                }
            } else {
                LOG.debug("Chained-plugin returned null ACLs!!");
            }
        }

        GdsPolicyEngine gdsPolicyEngine = getGdsPolicyEngine();

        if (gdsPolicyEngine != null) {
            RangerResourceACLs gdsACLs = gdsPolicyEngine.getResourceACLs(request);

            if (gdsACLs != null) {
                if (ret != null) {
                    ret = getMergedResourceACLs(ret, gdsACLs);
                } else {
                    ret = gdsACLs;
                }
            }
        }

        return ret;
    }

    public Set<String> getRolesFromUserAndGroups(String user, Set<String> groups) {
        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            return policyEngine.getRolesFromUserAndGroups(user, groups);
        }

        return null;
    }

    public RangerRoles getRangerRoles() {
        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            return policyEngine.getRangerRoles();
        }

        return null;
    }

    public Set<RangerRole> getRangerRoleForPrincipal(String principal, String type) {
        Set<RangerRole>          ret          = new HashSet<>();
        RangerPolicyEngine       policyEngine = this.policyEngine;
        RangerRoles              roles        = policyEngine != null ? policyEngine.getRangerRoles() : null;
        Set<RangerRole>          rangerRoles  = roles != null ? roles.getRangerRoles() : null;
        Map<String, Set<String>> roleMapping  = null;

        if (rangerRoles != null) {
            RangerPluginContext rangerPluginContext = policyEngine.getPluginContext();

            if (rangerPluginContext != null) {
                RangerAuthContext rangerAuthContext = rangerPluginContext.getAuthContext();

                if (rangerAuthContext != null) {
                    RangerRolesUtil rangerRolesUtil = rangerAuthContext.getRangerRolesUtil();
                    if (rangerRolesUtil != null) {
                        switch (type) {
                            case "USER":
                                roleMapping = rangerRolesUtil.getUserRoleMapping();
                                break;
                            case "GROUP":
                                roleMapping = rangerRolesUtil.getGroupRoleMapping();
                                break;
                            case "ROLE":
                                roleMapping = rangerRolesUtil.getRoleRoleMapping();
                                break;
                        }
                    }
                }
            }

            if (roleMapping != null) {
                Set<String> principalRoles = roleMapping.get(principal);

                if (CollectionUtils.isNotEmpty(principalRoles)) {
                    for (String role : principalRoles) {
                        for (RangerRole rangerRole : rangerRoles) {
                            if (rangerRole.getName().equals(role)) {
                                ret.add(rangerRole);
                            }
                        }
                    }
                }
            }
        }

        return ret;
    }

    public boolean isServiceAdmin(String userName) {
        boolean ret = false;

        RangerPolicyEngine policyEngine = this.policyEngine;

        if (policyEngine != null) {
            RangerPolicyEngineImpl rangerPolicyEngine = (RangerPolicyEngineImpl) policyEngine;

            ret = rangerPolicyEngine.isServiceAdmin(userName);
        }

        return ret;
    }

    public RangerRole createRole(RangerRole request, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.createRole({})", request);

        RangerRole ret = getAdminClient().createRole(request);

        LOG.debug("<== RangerBasePlugin.createRole({})", request);

        return ret;
    }

    public void dropRole(String execUser, String roleName, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.dropRole({})", roleName);

        getAdminClient().dropRole(execUser, roleName);

        LOG.debug("<== RangerBasePlugin.dropRole({})", roleName);
    }

    public List<String> getUserRoles(String execUser, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.getUserRoleNames({})", execUser);

        final List<String> ret = getAdminClient().getUserRoles(execUser);

        LOG.debug("<== RangerBasePlugin.getUserRoleNames({})", execUser);

        return ret;
    }

    public List<String> getAllRoles(String execUser, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.getAllRoles()");

        final List<String> ret = getAdminClient().getAllRoles(execUser);

        LOG.debug("<== RangerBasePlugin.getAllRoles()");

        return ret;
    }

    public RangerRole getRole(String execUser, String roleName, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.getPrincipalsForRole({})", roleName);

        final RangerRole ret = getAdminClient().getRole(execUser, roleName);

        LOG.debug("<== RangerBasePlugin.getPrincipalsForRole({})", roleName);

        return ret;
    }

    public void grantRole(GrantRevokeRoleRequest request, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.grantRole({})", request);

        getAdminClient().grantRole(request);

        LOG.debug("<== RangerBasePlugin.grantRole({})", request);
    }

    public void revokeRole(GrantRevokeRoleRequest request, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.revokeRole({})", request);

        getAdminClient().revokeRole(request);

        LOG.debug("<== RangerBasePlugin.revokeRole({})", request);
    }

    public void grantAccess(GrantRevokeRequest request, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.grantAccess({})", request);

        boolean isSuccess = false;

        try {
            RangerPolicyEngine policyEngine = this.policyEngine;

            if (policyEngine != null) {
                request.setZoneName(policyEngine.getUniquelyMatchedZoneName(request));
            }

            getAdminClient().grantAccess(request);

            isSuccess = true;
        } finally {
            auditGrantRevoke(request, "grant", isSuccess, resultProcessor);
        }

        LOG.debug("<== RangerBasePlugin.grantAccess({})", request);
    }

    public void revokeAccess(GrantRevokeRequest request, RangerAccessResultProcessor resultProcessor) throws Exception {
        LOG.debug("==> RangerBasePlugin.revokeAccess({})", request);

        boolean isSuccess = false;

        try {
            RangerPolicyEngine policyEngine = this.policyEngine;

            if (policyEngine != null) {
                request.setZoneName(policyEngine.getUniquelyMatchedZoneName(request));
            }

            getAdminClient().revokeAccess(request);

            isSuccess = true;
        } finally {
            auditGrantRevoke(request, "revoke", isSuccess, resultProcessor);
        }

        LOG.debug("<== RangerBasePlugin.revokeAccess({})", request);
    }

    public void registerAuthContextEventListener(RangerAuthContextListener authContextListener) {
        this.pluginContext.setAuthContextListener(authContextListener);
    }

    public void refreshPoliciesAndTags() {
        LOG.debug("==> refreshPoliciesAndTags()");

        try {
            long oldPolicyVersion = getPoliciesVersion();

            // Synch-up policies
            if (refresher != null) {
                refresher.syncPoliciesWithAdmin(accessTrigger);
            }

            long newPolicyVersion = getPoliciesVersion();

            if (oldPolicyVersion == newPolicyVersion) {
                // Synch-up tags
                RangerTagEnricher tagEnricher = getTagEnricher();

                if (tagEnricher != null) {
                    tagEnricher.syncTagsWithAdmin(accessTrigger);
                }
            }
        } catch (InterruptedException exception) {
            LOG.error("Failed to update policy-engine, continuing to use old policy-engine and/or tags", exception);
        }

        LOG.debug("<== refreshPoliciesAndTags()");
    }

    public boolean logErrorMessage(String message) {
        LogHistory log = logHistoryList.computeIfAbsent(message, k -> new LogHistory());

        if ((System.currentTimeMillis() - log.lastLogTime) > logInterval) {
            log.lastLogTime = System.currentTimeMillis();

            int counter = log.counter;

            log.counter = 0;

            if (counter > 0) {
                message += ". Messages suppressed before: " + counter;
            }

            LOG.error(message);

            return true;
        } else {
            log.counter++;
        }

        return false;
    }

    public RangerTagEnricher getTagEnricher() {
        RangerTagEnricher ret         = null;
        RangerAuthContext authContext = getCurrentRangerAuthContext();

        if (authContext != null) {
            Map<RangerContextEnricher, Object> contextEnricherMap = authContext.getRequestContextEnrichers();

            if (MapUtils.isNotEmpty(contextEnricherMap)) {
                Set<RangerContextEnricher> contextEnrichers = contextEnricherMap.keySet();

                for (RangerContextEnricher enricher : contextEnrichers) {
                    if (enricher instanceof RangerTagEnricher) {
                        ret = (RangerTagEnricher) enricher;

                        break;
                    }
                }
            }
        }
        return ret;
    }

    public RangerUserStoreEnricher getUserStoreEnricher() {
        RangerUserStoreEnricher ret         = null;
        RangerAuthContext       authContext = getCurrentRangerAuthContext();

        if (authContext != null) {
            Map<RangerContextEnricher, Object> contextEnricherMap = authContext.getRequestContextEnrichers();

            if (MapUtils.isNotEmpty(contextEnricherMap)) {
                Set<RangerContextEnricher> contextEnrichers = contextEnricherMap.keySet();

                for (RangerContextEnricher enricher : contextEnrichers) {
                    if (enricher instanceof RangerUserStoreEnricher) {
                        ret = (RangerUserStoreEnricher) enricher;

                        ret.getRangerUserStore();

                        break;
                    }
                }
            }
        }

        return ret;
    }

    public RangerGdsEnricher getGdsEnricher() {
        RangerGdsEnricher ret         = null;
        RangerAuthContext authContext = getCurrentRangerAuthContext();

        if (authContext != null) {
            Map<RangerContextEnricher, Object> contextEnricherMap = authContext.getRequestContextEnrichers();

            if (MapUtils.isNotEmpty(contextEnricherMap)) {
                Set<RangerContextEnricher> contextEnrichers = contextEnricherMap.keySet();

                for (RangerContextEnricher enricher : contextEnrichers) {
                    if (enricher instanceof RangerGdsEnricher) {
                        ret = (RangerGdsEnricher) enricher;

                        break;
                    }
                }
            }
        }

        return ret;
    }

    public GdsPolicyEngine getGdsPolicyEngine() {
        RangerGdsEnricher gdsEnricher = getGdsEnricher();

        return gdsEnricher != null ? gdsEnricher.getGdsPolicyEngine() : null;
    }

    public Map<String, String> getServiceConfigs() {
        return serviceConfigs;
    }

    public Long getPolicyVersion() {
        RangerPolicyEngine policyEngine = this.policyEngine;

        return policyEngine == null ? -1L : policyEngine.getPolicyVersion();
    }

    protected RangerPolicyEngine getPolicyEngine() {
        return policyEngine;
    }

    private void setServiceConfigs(Map<String, String> serviceConfigs) {
        Map<String, String> oldServiceConfigs = this.serviceConfigs;

        this.serviceConfigs = serviceConfigs != null ? serviceConfigs : new HashMap<>();

        RangerAuthContext authContext = this.pluginContext.getAuthContext();

        if (authContext != null && !Objects.equals(oldServiceConfigs, this.serviceConfigs)) {
            authContext.onServiceConfigsUpdate(this.serviceConfigs);
        }
    }

    private void auditGrantRevoke(GrantRevokeRequest request, String action, boolean isSuccess, RangerAccessResultProcessor resultProcessor) {
        if (request != null && resultProcessor != null) {
            RangerAccessRequestImpl accessRequest = new RangerAccessRequestImpl();

            accessRequest.setResource(new RangerAccessResourceImpl(StringUtil.toStringObjectMap(request.getResource())));
            accessRequest.setUser(request.getGrantor());
            accessRequest.setAccessType(RangerPolicyEngine.ANY_ACCESS);
            accessRequest.setAction(action);
            accessRequest.setClientIPAddress(request.getClientIPAddress());
            accessRequest.setClientType(request.getClientType());
            accessRequest.setRequestData(request.getRequestData());
            accessRequest.setSessionId(request.getSessionId());

            // call isAccessAllowed() to determine if audit is enabled or not
            RangerAccessResult accessResult = isAccessAllowed(accessRequest, null);

            if (accessResult != null && accessResult.getIsAudited()) {
                accessRequest.setAccessType(action);
                accessResult.setIsAllowed(isSuccess);

                if (!isSuccess) {
                    accessResult.setPolicyId(-1);
                }

                resultProcessor.processResult(accessResult);
            }
        }
    }

    private RangerServiceDef getDefaultServiceDef() {
        RangerServiceDef ret = null;

        if (StringUtils.isNotBlank(getServiceType())) {
            try {
                ret = EmbeddedServiceDefsUtil.instance().getEmbeddedServiceDef(getServiceType());
            } catch (Exception exp) {
                LOG.error("Could not get embedded service-def for {}", getServiceType());
            }
        }
        return ret;
    }

    private ServicePolicies getDefaultSvcPolicies() {
        ServicePolicies  ret        = null;
        RangerServiceDef serviceDef = getServiceDef();

        if (serviceDef == null) {
            serviceDef = getDefaultServiceDef();
        }

        if (serviceDef != null) {
            ret = new ServicePolicies();

            ret.setServiceDef(serviceDef);
            ret.setServiceName(getServiceName());
            ret.setPolicies(new ArrayList<>());
        }

        return ret;
    }

    private Set<String> toSet(String value) {
        return StringUtils.isNotBlank(value) ? StringUtil.toSet(value) : Collections.emptySet();
    }

    private RangerAdminClient getAdminClient() throws Exception {
        PolicyRefresher   refresher = this.refresher;
        RangerAdminClient admin     = refresher == null ? null : refresher.getRangerAdminClient();

        if (admin == null) {
            throw new Exception("ranger-admin client is null");
        }
        return admin;
    }

    private List<RangerChainedPlugin> initChainedPlugins() {
        List<RangerChainedPlugin> ret                      = new ArrayList<>();
        String                    chainedServicePropPrefix = pluginConfig.getPropertyPrefix() + ".chained.services";

        for (String chainedService : StringUtil.toList(pluginConfig.get(chainedServicePropPrefix))) {
            if (StringUtils.isBlank(chainedService)) {
                continue;
            }

            String className = pluginConfig.get(chainedServicePropPrefix + "." + chainedService + ".impl");

            if (StringUtils.isBlank(className)) {
                LOG.error("Ignoring chained service {}: no impl class specified", chainedService);

                continue;
            }

            try {
                @SuppressWarnings("unchecked")
                Class<RangerChainedPlugin> pluginClass = (Class<RangerChainedPlugin>) Class.forName(className);
                RangerChainedPlugin chainedPlugin = pluginClass.getConstructor(RangerBasePlugin.class, String.class).newInstance(this, chainedService);

                ret.add(chainedPlugin);
            } catch (Throwable t) {
                LOG.error("initChainedPlugins(): error instantiating plugin impl {}", className, t);
            }
        }

        return ret;
    }

    private void updateResultFromChainedResult(RangerAccessResult result, RangerAccessResult chainedResult) {
        boolean overrideResult = false;
        int     policyType     = result.getPolicyType();

        if (chainedResult.getIsAccessDetermined()) { // only if chained-result is definitive
            // override if chained-result is by a higher priority policy or result is not definitive or the result is not-allowed and no matching Ranger policy found
            overrideResult = chainedResult.getPolicyPriority() > result.getPolicyPriority() || !result.getIsAccessDetermined() || (!result.getIsAllowed() && result.getPolicyId() == -1L);

            if (!overrideResult) {
                // override if chained-result is from the same policy priority, and if denies access with a specific policy id
                if (chainedResult.getPolicyPriority() == result.getPolicyPriority() && (!chainedResult.getIsAllowed() && chainedResult.getPolicyId() != -1L)) {
                    // let's not override if result is already denied
                    if (result.getIsAllowed()) {
                        overrideResult = true;
                    }
                }
            }
        }

        if (overrideResult) {
            result.setIsAllowed(chainedResult.getIsAllowed());
            result.setIsAccessDetermined(chainedResult.getIsAccessDetermined());
            result.setPolicyId(chainedResult.getPolicyId());
            result.setPolicyVersion(chainedResult.getPolicyVersion());
            result.setPolicyPriority(chainedResult.getPolicyPriority());
            result.setZoneName(chainedResult.getZoneName());

            if (policyType == RangerPolicy.POLICY_TYPE_DATAMASK) {
                result.setMaskType(chainedResult.getMaskType());
                result.setMaskCondition(chainedResult.getMaskCondition());
                result.setMaskedValue(chainedResult.getMaskedValue());
            } else if (policyType == RangerPolicy.POLICY_TYPE_ROWFILTER) {
                result.setFilterExpr(chainedResult.getFilterExpr());
            }
        }

        if (!result.getIsAuditedDetermined() && chainedResult.getIsAuditedDetermined()) {
            result.setIsAudited(chainedResult.getIsAudited());
            result.setAuditPolicyId(chainedResult.getAuditPolicyId());
        }
    }

    private static void overrideACLs(final RangerResourceACLs chainedResourceACLs, RangerResourceACLs baseResourceACLs, final RangerRolesUtil.ROLES_FOR userType) {
        LOG.debug("==> RangerBasePlugin.overrideACLs(isUser={})", userType.name());

        Map<String, Map<String, RangerResourceACLs.AccessResult>> chainedACLs = null;
        Map<String, Map<String, RangerResourceACLs.AccessResult>> baseACLs    = null;

        switch (userType) {
            case USER:
                chainedACLs = chainedResourceACLs.getUserACLs();
                baseACLs = baseResourceACLs.getUserACLs();
                break;
            case GROUP:
                chainedACLs = chainedResourceACLs.getGroupACLs();
                baseACLs = baseResourceACLs.getGroupACLs();
                break;
            case ROLE:
                chainedACLs = chainedResourceACLs.getRoleACLs();
                baseACLs = baseResourceACLs.getRoleACLs();
                break;
            default:
                break;
        }

        for (Map.Entry<String, Map<String, RangerResourceACLs.AccessResult>> chainedPermissionsMap : chainedACLs.entrySet()) {
            String                                       name               = chainedPermissionsMap.getKey();
            Map<String, RangerResourceACLs.AccessResult> chainedPermissions = chainedPermissionsMap.getValue();
            Map<String, RangerResourceACLs.AccessResult> basePermissions    = baseACLs.get(name);

            for (Map.Entry<String, RangerResourceACLs.AccessResult> chainedPermission : chainedPermissions.entrySet()) {
                String                          chainedAccessType   = chainedPermission.getKey();
                RangerResourceACLs.AccessResult chainedAccessResult = chainedPermission.getValue();
                RangerResourceACLs.AccessResult baseAccessResult    = basePermissions == null ? null : basePermissions.get(chainedAccessType);

                final boolean useChainedAccessResult;

                if (baseAccessResult == null) {
                    useChainedAccessResult = true;
                } else {
                    if (chainedAccessResult.getPolicy().getPolicyPriority() > baseAccessResult.getPolicy().getPolicyPriority()) {
                        useChainedAccessResult = true;
                    } else if (chainedAccessResult.getPolicy().getPolicyPriority().equals(baseAccessResult.getPolicy().getPolicyPriority())) {
                        if (chainedAccessResult.getResult() == baseAccessResult.getResult()) {
                            useChainedAccessResult = true;
                        } else {
                            useChainedAccessResult = chainedAccessResult.getResult() == RangerPolicyEvaluator.ACCESS_DENIED;
                        }
                    } else { // chainedAccessResult.getPolicy().getPolicyPriority() < baseAccessResult.getPolicy().getPolicyPriority()
                        useChainedAccessResult = false;
                    }
                }

                final RangerResourceACLs.AccessResult finalAccessResult = useChainedAccessResult ? chainedAccessResult : baseAccessResult;

                switch (userType) {
                    case USER:
                        baseResourceACLs.setUserAccessInfo(name, chainedAccessType, finalAccessResult.getResult(), finalAccessResult.getPolicy());
                        break;
                    case GROUP:
                        baseResourceACLs.setGroupAccessInfo(name, chainedAccessType, finalAccessResult.getResult(), finalAccessResult.getPolicy());
                        break;
                    case ROLE:
                        baseResourceACLs.setRoleAccessInfo(name, chainedAccessType, finalAccessResult.getResult(), finalAccessResult.getPolicy());
                        break;
                    default:
                        break;
                }
            }
        }

        LOG.debug("<== RangerBasePlugin.mergeACLsOneWay(isUser={})", userType.name());
    }

    private static AuditProviderFactory getAuditProviderFactory(String serviceName) {
        AuditProviderFactory ret = AuditProviderFactory.getInstance();

        if (!ret.isInitDone()) {
            LOG.warn("RangerBasePlugin.getAuditProviderFactory(serviceName={}): audit not initialized yet. Will use stand-alone audit factory", serviceName);

            ret = StandAloneAuditProviderFactory.getInstance();

            if (!ret.isInitDone()) {
                RangerAuditConfig conf = new RangerAuditConfig();

                if (conf.isInitSuccess()) {
                    ret.init(conf.getProperties(), "StandAlone");
                }
            }
        }

        return ret;
    }

    private static final class LogHistory {
        long lastLogTime;
        int  counter;
    }
}
