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

package org.apache.ranger.plugin.conditionevaluator;

import org.apache.ranger.plugin.contextenricher.RangerTagForEval;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.util.RangerAccessRequestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

// Policy Condition to check if resource Tags does contain any of the policy Condition Tags
public class RangerAnyOfExpectedTagsPresentConditionEvaluator extends RangerAbstractConditionEvaluator {
    private static final Logger LOG = LoggerFactory.getLogger(RangerAnyOfExpectedTagsPresentConditionEvaluator.class);

    private final Set<String> policyConditionTags = new HashSet<>();

    @Override
    public void init() {
        LOG.debug("==> RangerAnyOfExpectedTagsPresentConditionEvaluator.init({})", condition);

        super.init();

        if (condition != null) {
            for (String value : condition.getValues()) {
                policyConditionTags.add(value.trim());
            }
        }

        LOG.debug("<== RangerAnyOfExpectedTagsPresentConditionEvaluator.init({}): Tags[{}]", condition, policyConditionTags);
    }

    @Override
    public boolean isMatched(RangerAccessRequest request) {
        LOG.debug("==> RangerAnyOfExpectedTagsPresentConditionEvaluator.isMatched({})", request);

        boolean               matched      = false;
        Set<RangerTagForEval> resourceTags = RangerAccessRequestUtil.getRequestTagsFromContext(request.getContext());

        if (resourceTags != null) {
            // check if resource Tags does contain any of the policy Condition Tags
            for (RangerTagForEval tag : resourceTags) {
                if (policyConditionTags.contains(tag.getType())) {
                    matched = true;
                    break;
                }
            }
        }

        LOG.debug("<== RangerAnyOfExpectedTagsPresentConditionEvaluator.isMatched({}): {}", request, matched);

        return matched;
    }
}
