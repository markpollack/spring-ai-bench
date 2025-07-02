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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springaicommunity.bench.core.spec.SuccessSpec;

import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class SuccessVerifier {

    private final TaskLauncher launcher;

    SuccessVerifier(TaskLauncher l) { this.launcher = l; }

    boolean verify(Path ws, SuccessSpec spec, Duration timeout) throws Exception {
        AppDefinition def = new AppDefinition("verify", Map.of());
        Resource res = new FileSystemResource("/usr/bin/env"); // any placeholder exe
        AppDeploymentRequest req = new AppDeploymentRequest(def, res,
                Map.of("working.dir", ws.toString()), List.of(spec.cmd()));
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
                throw new IOException("Verification task " + id + " timed out after " + timeout.toSeconds() + " seconds");
            }
            Thread.sleep(1000); // Poll every second
        }

        return status.getState() == LaunchState.complete;
    }
}
