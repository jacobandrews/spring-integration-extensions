/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.hazelcast.inbound;

import javax.annotation.Resource;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.hazelcast.HazelcastIntegrationTestUser;
import org.springframework.integration.hazelcast.inbound.util.HazelcastInboundChannelAdapterTestUtils;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.hazelcast.core.IMap;

/**
 * Hazelcast Distributed SQL Inbound Channel Adapter Test
 *
 * @author Eren Avsarogullari
 * @since 1.0.0
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
@DirtiesContext
public class HazelcastDistributedSQLInboundChannelAdapterTests {

    @Autowired
    private PollableChannel dsMapChannel1;

    @Autowired
    private PollableChannel dsMapChannel2;

    @Autowired
    private PollableChannel dsMapChannel3;

    @Autowired
    private PollableChannel dsMapChannel4;

    @Resource
    private IMap<Integer, HazelcastIntegrationTestUser> dsDistributedMap1;

    @Resource
    private IMap<Integer, HazelcastIntegrationTestUser> dsDistributedMap2;

    @Resource
    private IMap<Integer, HazelcastIntegrationTestUser> dsDistributedMap3;

    @Resource
    private IMap<Integer, HazelcastIntegrationTestUser> dsDistributedMap4;

    @Test
    public void testDistributedSQLForOnlyENTRYIterationType() {
        HazelcastInboundChannelAdapterTestUtils
            .testDistributedSQLForENTRYIterationType(dsDistributedMap1, dsMapChannel1);
    }

    @Test
    public void testDistributedSQLForOnlyKEYIterationType() {
        HazelcastInboundChannelAdapterTestUtils
            .testDistributedSQLForKEYIterationType(dsDistributedMap2, dsMapChannel2);
    }

    @Test
    public void testDistributedSQLForOnlyLOCAL_KEYIterationType() {
        HazelcastInboundChannelAdapterTestUtils
            .testDistributedSQLForLOCAL_KEYIterationType(dsDistributedMap3, dsMapChannel3);
    }

    @Test
    public void testDistributedSQLForOnlyVALUEIterationType() {
        HazelcastInboundChannelAdapterTestUtils
            .testDistributedSQLForVALUEIterationType(dsDistributedMap4, dsMapChannel4);
    }

}
