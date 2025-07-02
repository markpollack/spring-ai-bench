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
package org.springaicommunity.bench.core.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springaicommunity.bench.core.spec.AgentSpec;

import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class DummyPatchRunner implements AgentRunner {

    private final TaskLauncher launcher;

    DummyPatchRunner(TaskLauncher launcher) { this.launcher = launcher; }

    @Override
    public AgentResult run(Path ws, AgentSpec spec, Duration timeout) throws Exception {
        // 1. Write patch.sh into workspace
        Path script = ws.resolve("patch.sh");
        Files.writeString(script, """
            #!/bin/bash
            set -e
            sed -i 's/return Math.sqrt(/if ($1 < 0); then echo "neg"; exit 1; fi; return Math.sqrt(/' \
                src/main/java/org/springaicommunity/bench/example/calculator/Calculator.java
            """);
        script.toFile().setExecutable(true);

        // 2. Launch via LocalTaskLauncher
        AppDefinition def = new AppDefinition("dummy-agent", Map.of());
        Resource res = new FileSystemResource(script);
        AppDeploymentRequest req = new AppDeploymentRequest(def, res, Map.of(
                "working.dir", ws.toString()), List.of()); // inheritLogging true by default
        String id = launcher.launch(req);

        long start = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        TaskStatus status;
        while (true) {
            status = launcher.status(id);
            LaunchState state = status.getState();
            if (state == LaunchState.complete || state == LaunchState.failed || state == LaunchState.error) {
                break;
            }
            if (System.currentTimeMillis() - start > timeoutMillis) {
                launcher.cancel(id);
                throw new IOException("Task " + id + " timed out after " + timeout.toSeconds() + " seconds");
            }
            Thread.sleep(1000); // Poll every second
        }
        long dur = System.currentTimeMillis() - start;
        int exitCode = (status.getState() == LaunchState.complete) ? 0 : 1;
        return new AgentResult(exitCode, null, dur);
    }
}
