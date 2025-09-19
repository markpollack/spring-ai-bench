/*
 * Copyright 2024 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.bench.agents.claudecode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springaicommunity.bench.core.run.AgentResult;
import org.springaicommunity.bench.core.run.AgentRunner;
import org.springaicommunity.bench.core.spec.AgentSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for Claude Code agent runner.
 * Requires ANTHROPIC_API_KEY environment variable and claude CLI.
 */
@SpringBootTest
@Tag("agents-live")
@Tag("claude")
@ActiveProfiles("agents-live")  // Activate Spring profile for yolo settings
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@Timeout(120)  // Cap runtime at 2 minutes
class ClaudeCodeIntegrationTest {

    @Autowired
    private AgentRunner agentRunner;

    private Path tempWorkspace;

    @BeforeAll
    static void requireCli() {
        // Check if claude CLI is available
        assumeTrue(isCliAvailable("claude"), "Claude CLI not available on PATH");
    }

    @BeforeEach
    void setUp() throws Exception {
        tempWorkspace = Files.createTempDirectory("claude-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempWorkspace != null && Files.exists(tempWorkspace)) {
            Files.walk(tempWorkspace)
                .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        // Best effort cleanup
                    }
                });
        }
    }

    @Test
    void helloWorld_case_passes() throws Exception {
        // Create AgentSpec for hello world task
        AgentSpec spec = new AgentSpec(
            "hello-world",
            "Create a file named hello.txt in the current working directory with EXACT contents: Hello World!",
            null, // model - will use default
            null, // genParams
            null, // autoApprove
            null  // role
        );

        // Run the agent
        AgentResult result = agentRunner.run(tempWorkspace, spec, Duration.ofMinutes(2));

        // Verify the result
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.logFile()).exists();

        // Verify the hello.txt file was created with correct content
        Path helloFile = tempWorkspace.resolve("hello.txt");
        assertThat(helloFile).exists();

        String content = Files.readString(helloFile);
        assertThat(content).isEqualTo("Hello World!");
    }

    private static boolean isCliAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true)
                .start();
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}