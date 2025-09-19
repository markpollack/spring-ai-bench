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
package org.springaicommunity.bench.agents.adapter;

import org.springaicommunity.bench.agents.logging.SimpleLogCapture;
import org.springaicommunity.bench.agents.report.IndexPageGenerator;
import org.springaicommunity.bench.agents.report.MinimalHtmlReportGenerator;
import org.springaicommunity.bench.agents.report.MinimalJsonReportGenerator;
import org.springaicommunity.bench.agents.verifier.*;
import org.springaicommunity.bench.core.run.AgentRunner;
import org.springaicommunity.bench.core.run.AgentResult;
import org.springaicommunity.bench.core.spec.AgentSpec;
import org.springaicommunity.agents.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter that bridges between Spring AI Bench and Spring AI Agents APIs.
 * Uses verification system to determine success and captures detailed logs.
 */
public class AgentModelAdapter implements AgentRunner {

    private final AgentModel agentModel;
    private final SuccessVerifier verifier;

    public AgentModelAdapter(AgentModel agentModel, SuccessVerifier verifier) {
        this.agentModel = agentModel;
        this.verifier = verifier;
    }

    // Constructor for backward compatibility
    public AgentModelAdapter(AgentModel agentModel) {
        this.agentModel = agentModel;
        this.verifier = null; // Will use old fallback logic
    }

    @Override
    public AgentResult run(Path workspace, AgentSpec spec, Duration timeout) throws Exception {
        Instant startedAt = Instant.now();

        // Create run directory structure for reports
        UUID runId = UUID.randomUUID();
        Path runRoot = workspace.getParent().resolve("bench-reports").resolve(runId.toString());
        // For verification context, we need to point to the actual workspace
        // Make workspace relative to runRoot's parent for verification
        Path workspaceRel = runRoot.getParent().relativize(workspace);
        Path actualWorkspace = workspace;

        SimpleLogCapture logger = null;

        try {
            // Set up logging
            logger = new SimpleLogCapture(runRoot, runId);
            logger.log("ADAPTER", "Starting " + agentModel.getClass().getSimpleName());

            // Clean and create workspace
            cleanWorkspace(actualWorkspace, logger);

            // Create AgentOptions for the request
            AgentOptions options = createAgentOptions(spec, timeout);

            // Convert AgentSpec to AgentTaskRequest
            var request = new AgentTaskRequest(
                spec.prompt(),
                actualWorkspace,
                options
            );

            logger.log("AGENT", "Executing agent task");

            // Call the agent
            AgentResponse response = agentModel.call(request);

            // Verify results
            boolean success;
            VerificationResult verificationResult = null;

            if (verifier != null) {
                logger.log("VERIFIER", "Starting verification");
                // Create a verification context that points to the actual workspace
                VerificationContext context = new VerificationContext(actualWorkspace.getParent(), actualWorkspace.getFileName(), startedAt);
                verificationResult = verifier.verify(context);
                success = verificationResult.success();

                // Log verification summary
                String checkSummary = verificationResult.checks().stream()
                    .map(check -> check.name() + ":" + (check.pass() ? "PASS" : "FAIL"))
                    .reduce((a, b) -> a + " " + b)
                    .orElse("no checks");
                logger.log("VERIFIER", checkSummary);
            } else {
                // Fallback to old heuristic logic
                success = isSuccessfulResponse(response);
                logger.log("VERIFIER", "Using fallback heuristic: " + (success ? "PASS" : "FAIL"));
            }

            Instant finishedAt = Instant.now();
            long duration = Duration.between(startedAt, finishedAt).toMillis();

            logger.log("RESULT", (success ? "SUCCESS" : "FAILURE") + ": " +
                (verificationResult != null ? verificationResult.reason() : "heuristic check"));

            if (logger != null) {
                logger.writeFooter();
            }

            // Generate reports
            generateReports(runId, "hello-world", success, startedAt, finishedAt, duration, verificationResult, runRoot);

            // Return result with log path
            return new AgentResult(success ? 0 : 1, runRoot.resolve("run.log"), duration);

        } catch (Exception e) {
            Instant finishedAt = Instant.now();
            long duration = Duration.between(startedAt, finishedAt).toMillis();

            if (logger != null) {
                logger.log("ERROR", "Agent execution failed: " + e.getMessage());
                logger.writeFooter();
            }

            return new AgentResult(1, runRoot.resolve("run.log"), duration);
        }
    }

    private void cleanWorkspace(Path workspace, SimpleLogCapture logger) throws Exception {
        if (Files.exists(workspace)) {
            logger.log("WORKSPACE", "Clearing existing workspace");
            Files.walk(workspace)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Log but don't fail - best effort cleanup
                    }
                });
        }
        Files.createDirectories(workspace);
        logger.log("WORKSPACE", "Created clean workspace");
    }

    private AgentOptions createAgentOptions(AgentSpec spec, Duration timeout) {
        return new AgentOptions() {
            @Override
            public String getWorkingDirectory() {
                return null; // workingDirectory is in the request itself
            }

            @Override
            public Duration getTimeout() {
                return timeout;
            }

            @Override
            public Map<String, String> getEnvironmentVariables() {
                return Map.of();
            }

            @Override
            public String getModel() {
                return spec.model() != null ? spec.model() : "default";
            }

            @Override
            public Map<String, Object> getExtras() {
                Map<String, Object> extras = new HashMap<>();
                if (spec.genParams() != null) {
                    extras.putAll(spec.genParams());
                }
                if (spec.autoApprove() != null) {
                    extras.put("autoApprove", spec.autoApprove());
                }
                if (spec.role() != null) {
                    extras.put("role", spec.role());
                }
                return extras;
            }
        };
    }

    /**
     * Fallback success determination for backward compatibility.
     */
    private boolean isSuccessfulResponse(AgentResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return false;
        }

        return response.getResults().stream()
            .anyMatch(generation -> {
                String content = generation.getOutput().toLowerCase();
                // Check metadata for explicit success flag
                Object success = generation.getMetadata().getProviderFields().get("success");
                if (success instanceof Boolean) {
                    return (Boolean) success;
                }
                // Fall back to content analysis
                return !content.contains("failed") && !content.contains("error");
            });
    }

    private void generateReports(UUID runId, String caseId, boolean success, Instant startedAt,
                               Instant finishedAt, long durationMs, VerificationResult verificationResult,
                               Path runRoot) {
        try {
            // Generate JSON report
            MinimalJsonReportGenerator.generate(runId, caseId, success, startedAt, finishedAt,
                durationMs, verificationResult, runRoot);

            // Generate HTML report
            MinimalHtmlReportGenerator.generate(runId, caseId, success, startedAt, finishedAt,
                durationMs, verificationResult, runRoot);

            // Update index page
            Path reportsBaseDir = runRoot.getParent();
            IndexPageGenerator.generate(reportsBaseDir);

        } catch (Exception e) {
            // Log error but don't fail the execution
            System.err.println("Failed to generate reports: " + e.getMessage());
        }
    }
}