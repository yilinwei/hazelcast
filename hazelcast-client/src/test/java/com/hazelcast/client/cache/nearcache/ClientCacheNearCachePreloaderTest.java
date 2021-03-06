/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.cache.nearcache;

import com.hazelcast.cache.ICache;
import com.hazelcast.cache.impl.HazelcastServerCacheManager;
import com.hazelcast.cache.impl.HazelcastServerCachingProvider;
import com.hazelcast.client.cache.impl.HazelcastClientCacheManager;
import com.hazelcast.client.cache.impl.HazelcastClientCachingProvider;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.HazelcastClientProxy;
import com.hazelcast.client.test.TestHazelcastFactory;
import com.hazelcast.config.CacheConfig;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.adapter.ICacheDataStructureAdapter;
import com.hazelcast.internal.nearcache.AbstractNearCachePreloaderTest;
import com.hazelcast.internal.nearcache.NearCache;
import com.hazelcast.internal.nearcache.NearCacheManager;
import com.hazelcast.internal.nearcache.NearCacheTestContext;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.test.HazelcastParametersRunnerFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized.UseParametersRunnerFactory;

import javax.cache.spi.CachingProvider;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;

import static com.hazelcast.cache.CacheUtil.getDistributedObjectName;
import static com.hazelcast.config.EvictionConfig.MaxSizePolicy.USED_NATIVE_MEMORY_PERCENTAGE;
import static com.hazelcast.config.EvictionPolicy.LRU;
import static com.hazelcast.config.InMemoryFormat.NATIVE;
import static com.hazelcast.nio.IOUtil.toFileName;

@RunWith(Parameterized.class)
@UseParametersRunnerFactory(HazelcastParametersRunnerFactory.class)
@Category({QuickTest.class, ParallelTest.class})
public class ClientCacheNearCachePreloaderTest extends AbstractNearCachePreloaderTest<Data, String> {

    private final String cacheFileName = toFileName(getDistributedObjectName(defaultNearCache));
    private final File storeFile = new File("nearCache-" + cacheFileName + ".store").getAbsoluteFile();
    private final File storeLockFile = new File(storeFile.getName() + ".lock").getAbsoluteFile();

    @Parameter
    public boolean invalidationOnChange;

    private final TestHazelcastFactory hazelcastFactory = new TestHazelcastFactory();

    @Parameters(name = "invalidationOnChange:{0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {false},
                {true}
        });
    }

    @Before
    public void setUp() {
        nearCacheConfig = getNearCacheConfig(invalidationOnChange, KEY_COUNT, storeFile.getParent());
    }

    @After
    public void tearDown() {
        hazelcastFactory.shutdownAll();
    }

    @Override
    protected File getStoreFile() {
        return storeFile;
    }

    @Override
    protected File getStoreLockFile() {
        return storeLockFile;
    }

    @Override
    protected <K, V> NearCacheTestContext<K, V, Data, String> createContext(boolean createClient) {
        CacheConfig<K, V> cacheConfig = createCacheConfig(nearCacheConfig);

        HazelcastInstance member = hazelcastFactory.newHazelcastInstance(getConfig());
        CachingProvider memberProvider = HazelcastServerCachingProvider.createCachingProvider(member);
        HazelcastServerCacheManager memberCacheManager = (HazelcastServerCacheManager) memberProvider.getCacheManager();
        ICache<K, V> memberCache = memberCacheManager.createCache(nearCacheConfig.getName(), cacheConfig);

        if (!createClient) {
            return new NearCacheTestContext<K, V, Data, String>(
                    getSerializationService(member),
                    member,
                    new ICacheDataStructureAdapter<K, V>(memberCache),
                    false,
                    null,
                    null);
        }

        NearCacheTestContext<K, V, Data, String> clientContext = createClientContext(cacheConfig);
        return new NearCacheTestContext<K, V, Data, String>(
                clientContext.serializationService,
                clientContext.nearCacheInstance,
                member,
                clientContext.nearCacheAdapter,
                new ICacheDataStructureAdapter<K, V>(memberCache),
                false,
                clientContext.nearCache,
                clientContext.nearCacheManager,
                clientContext.cacheManager,
                memberCacheManager);
    }

    @Override
    protected <K, V> NearCacheTestContext<K, V, Data, String> createClientContext() {
        CacheConfig<K, V> cacheConfig = createCacheConfig(nearCacheConfig);
        return createClientContext(cacheConfig);
    }

    protected ClientConfig getClientConfig() {
        return new ClientConfig();
    }

    private <K, V> CacheConfig<K, V> createCacheConfig(NearCacheConfig nearCacheConfig) {
        CacheConfig<K, V> cacheConfig = new CacheConfig<K, V>()
                .setName(nearCacheConfig.getName())
                .setInMemoryFormat(nearCacheConfig.getInMemoryFormat());

        if (nearCacheConfig.getInMemoryFormat() == NATIVE) {
            cacheConfig.getEvictionConfig()
                    .setEvictionPolicy(LRU)
                    .setMaximumSizePolicy(USED_NATIVE_MEMORY_PERCENTAGE)
                    .setSize(90);
        }

        return cacheConfig;
    }

    private <K, V> NearCacheTestContext<K, V, Data, String> createClientContext(CacheConfig<K, V> cacheConfig) {
        ClientConfig clientConfig = getClientConfig()
                .addNearCacheConfig(nearCacheConfig);

        HazelcastClientProxy client = (HazelcastClientProxy) hazelcastFactory.newHazelcastClient(clientConfig);

        NearCacheManager nearCacheManager = client.client.getNearCacheManager();
        CachingProvider provider = HazelcastClientCachingProvider.createCachingProvider(client);
        HazelcastClientCacheManager cacheManager = (HazelcastClientCacheManager) provider.getCacheManager();
        String cacheNameWithPrefix = cacheManager.getCacheNameWithPrefix(nearCacheConfig.getName());

        ICache<K, V> clientCache = cacheManager.createCache(nearCacheConfig.getName(), cacheConfig);

        NearCache<Data, String> nearCache = nearCacheManager.getNearCache(cacheNameWithPrefix);

        return new com.hazelcast.internal.nearcache.NearCacheTestContext<K, V, Data, String>(
                client.getSerializationService(),
                client,
                null,
                new ICacheDataStructureAdapter<K, V>(clientCache),
                null,
                false,
                nearCache,
                nearCacheManager,
                cacheManager,
                null);
    }
}
