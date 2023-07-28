package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.apache.shiro.config.Ini;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;

class MainTest {

    private static final Map<Integer, ZooKeeperServerConfig> MAP = new HashMap<>();

    @BeforeAll
    static void beforeAll() {
        for (int index = 1; index <= 3; index++) {
            MAP.put(index, new ZooKeeperServerConfig("127.0.0.1", 5000 + index, 6000 + index, 7000 + index,
                                                     null, null));
        }
    }

    @AfterAll
    static void afterAll() throws Exception {
        Thread.sleep(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1,2,3})
    void testMain(int index) {
        CentralDogma dogma = new CentralDogmaBuilder(new File("./data" + index))
                .port(new ServerPort(8000 + index, SessionProtocol.HTTP))
                .webAppEnabled(true)
                .replication(new ZooKeeperReplicationConfig(index, MAP))
                .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                    final Ini iniConfig = new Ini();
                    iniConfig.addSection("users").put(USERNAME, PASSWORD);
                    return iniConfig;
                })).build();
        dogma.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> dogma.stop().join()));
    }
}
