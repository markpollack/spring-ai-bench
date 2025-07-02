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
package org.springaicommunity.bench.core.exec.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springaicommunity.bench.core.exec.*;
import org.springaicommunity.bench.core.exec.customizer.ExecSpecCustomizer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mock-heavy unit tests for LocalSandbox focusing on logic without actual process execution.
 */
class LocalSandboxTest {

    @TempDir
    Path tempDir;

    private Process mockProcess;
    private ProcessBuilder mockProcessBuilder;
    private LocalSandbox sandbox;

    @BeforeEach
    void setUp() throws IOException {
        mockProcess = mock(Process.class);
        mockProcessBuilder = mock(ProcessBuilder.class);

        // Setup default mock behavior
        when(mockProcessBuilder.directory(any())).thenReturn(mockProcessBuilder);
        when(mockProcessBuilder.redirectErrorStream(anyBoolean())).thenReturn(mockProcessBuilder);
        when(mockProcessBuilder.environment()).thenReturn(new java.util.HashMap<>());
    }

    @Nested
    @DisplayName("Constructor behavior")
    class ConstructorTests {

        @Test
        @DisplayName("should be creatable with no customizers")
        void constructor_withNoCustomizers() {
            sandbox = LocalSandbox.builder().build();
            assertThat(sandbox).isNotNull();
            assertThat(sandbox.isClosed()).isFalse();
        }

        @Test
        @DisplayName("should be creatable with customizers")
        void constructor_withCustomizers() {
            ExecSpecCustomizer c1 = (spec) -> spec;
            ExecSpecCustomizer c2 = (spec) -> spec;
            sandbox = LocalSandbox.builder().customizers(List.of(c1, c2)).build();
            assertThat(sandbox).isNotNull();
        }

        @Test
        @DisplayName("should be creatable with a specific working directory")
        void constructor_withSpecificWorkingDirectory() {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            assertThat(sandbox.workDir()).isEqualTo(tempDir);
        }

        @Test
        @DisplayName("should create working directory if it does not exist")
        void constructor_createsWorkingDirectory() {
            Path newDir = tempDir.resolve("new-work-dir");
            assertThat(Files.exists(newDir)).isFalse();
            sandbox = LocalSandbox.builder().workingDirectory(newDir).build();
            assertThat(Files.exists(newDir)).isTrue();
        }
    }

    @Nested
    @DisplayName("Customizer application")
    class CustomizerApplicationTests {

        @Test
        @DisplayName("should apply customizers in order")
        void exec_appliesCustomizersInOrder() throws Exception {
            ExecSpecCustomizer addArg1 = (spec) -> spec.toBuilder().command(spec.command().get(0), "arg1").build();
            ExecSpecCustomizer addArg2 = (spec) -> {
                List<String> cmd = new ArrayList<>(spec.command());
                cmd.add("arg2");
                return spec.toBuilder().command(cmd).build();
            };

            sandbox = LocalSandbox.builder()
                    .workingDirectory(tempDir)
                    .customizer(addArg1)
                    .customizer(addArg2)
                    .build();

            // Mock successful process execution
            setupSuccessfulProcessMock();

            // Capture constructor arguments
            final List<List<String>> commandArguments = new ArrayList<>();

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class, (mock, context) -> {
                // Capture the command list from the constructor
                commandArguments.add((List<String>) context.arguments().get(0));

                // Stub fluent API and execution
                when(mock.directory(any())).thenReturn(mock);
                when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                when(mock.environment()).thenReturn(new HashMap<>());
                when(mock.start()).thenReturn(mockProcess);
            })) {
                ExecSpec spec = ExecSpec.of("echo", "hello");
                sandbox.exec(spec);
            }

            // Verify that the constructor was called with the correct, customized command
            assertThat(commandArguments).hasSize(1);
            assertThat(commandArguments.get(0)).containsExactly("echo", "arg1", "arg2");
        }

        @Test
        @DisplayName("should handle customizer that returns same instance")
        void exec_handlesIdentityCustomizer() throws Exception {
            ExecSpecCustomizer identityCustomizer = spec -> spec;
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).customizer(identityCustomizer).build();

            setupSuccessfulProcessMock();

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.of("echo", "test");
                ExecResult result = sandbox.exec(spec);

                assertThat(result.success()).isTrue();
            }
        }

        @Test
        @DisplayName("should fail if customizer produces empty command")
        void exec_failsIfCustomizerProducesEmptyCommand() throws IOException {
            ExecSpecCustomizer emptyCommandCustomizer = spec -> spec.toBuilder()
                    .command() // Empty command
                    .build();

            sandbox = LocalSandbox.builder().workingDirectory(tempDir).customizer(emptyCommandCustomizer).build();

            ExecSpec spec = ExecSpec.of("echo", "test");

            assertThatThrownBy(() -> sandbox.exec(spec))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Command cannot be null or empty");
        }
    }

    @Nested
    @DisplayName("Environment variable handling")
    class EnvironmentVariableTests {

        @Test
        @DisplayName("should set environment variables from ExecSpec")
        void exec_setsEnvironmentVariables() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            setupSuccessfulProcessMock();

            Map<String, String> capturedEnv = new java.util.HashMap<>();
            when(mockProcessBuilder.environment()).thenReturn(capturedEnv);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(capturedEnv);
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.builder()
                        .command("echo", "test")
                        .env("TEST_VAR", "test_value")
                        .env("ANOTHER_VAR", "another_value")
                        .build();

                sandbox.exec(spec);

                assertThat(capturedEnv)
                        .containsEntry("TEST_VAR", "test_value")
                        .containsEntry("ANOTHER_VAR", "another_value");
            }
        }

        @Test
        @DisplayName("should set MCP_TOOLS environment variable when MCP config present")
        void exec_setsMcpToolsEnvironmentVariable() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            setupSuccessfulProcessMock();

            Map<String, String> capturedEnv = new java.util.HashMap<>();
            when(mockProcessBuilder.environment()).thenReturn(capturedEnv);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(capturedEnv);
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.builder()
                        .command("echo", "test")
                        .mcp(McpConfig.of("brave", "filesystem", "github"))
                        .build();

                sandbox.exec(spec);

                assertThat(capturedEnv).containsEntry("MCP_TOOLS", "brave,filesystem,github");
            }
        }

        @Test
        @DisplayName("should not set MCP_TOOLS when no MCP config")
        void exec_doesNotSetMcpToolsWhenNoMcp() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            setupSuccessfulProcessMock();

            Map<String, String> capturedEnv = new java.util.HashMap<>();
            when(mockProcessBuilder.environment()).thenReturn(capturedEnv);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(capturedEnv);
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.of("echo", "test");

                sandbox.exec(spec);

                assertThat(capturedEnv).doesNotContainKey("MCP_TOOLS");
            }
        }

        @Test
        @DisplayName("should combine ExecSpec env and MCP_TOOLS")
        void exec_combinesExecSpecEnvAndMcpTools() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            setupSuccessfulProcessMock();

            Map<String, String> capturedEnv = new java.util.HashMap<>();
            when(mockProcessBuilder.environment()).thenReturn(capturedEnv);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(capturedEnv);
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.builder()
                        .command("echo", "test")
                        .env("CUSTOM_VAR", "custom_value")
                        .mcp(McpConfig.of("brave"))
                        .build();

                sandbox.exec(spec);

                assertThat(capturedEnv)
                        .containsEntry("CUSTOM_VAR", "custom_value")
                        .containsEntry("MCP_TOOLS", "brave");
            }
        }
    }

    @Nested
    @DisplayName("Process execution and results")
    class ProcessExecutionTests {

        @Test
        @DisplayName("should return successful result for zero exit code")
        void exec_returnsSuccessfulResult() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            setupSuccessfulProcessMock();

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.of("echo", "hello");
                ExecResult result = sandbox.exec(spec);

                assertThat(result.exitCode()).isEqualTo(0);
                assertThat(result.success()).isTrue();
                assertThat(result.mergedLog()).isEqualTo("hello world\n");
                assertThat(result.duration()).isGreaterThan(Duration.ZERO);
            }
        }

        @Test
        @DisplayName("should return failure result for non-zero exit code")
        void exec_returnsFailureResult() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();

            when(mockProcess.waitFor()).thenReturn(1);
            when(mockProcess.exitValue()).thenReturn(1);
            when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream("error output".getBytes()));
            when(mockProcess.isAlive()).thenReturn(false);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.of("false");
                ExecResult result = sandbox.exec(spec);

                assertThat(result.exitCode()).isEqualTo(1);
                assertThat(result.failed()).isTrue();
                assertThat(result.mergedLog()).isEqualTo("error output");
            }
        }

        @Test
        @DisplayName("should throw timeout exception when process times out")
        void exec_throwsTimeoutException() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();

            // Mock timeout scenario
            when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
            when(mockProcess.isAlive()).thenReturn(true);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.builder()
                        .command("sleep", "10")
                        .timeout(Duration.ofSeconds(1))
                        .build();

                assertThatThrownBy(() -> sandbox.exec(spec))
                        .isInstanceOf(TimeoutException.class)
                        .hasMessage("Process timed out after PT1S");

                // Verify process was forcibly destroyed
                verify(mockProcess).destroyForcibly();
            }
        }

        @Test
        @DisplayName("should handle process without timeout")
        void exec_handlesProcessWithoutTimeout() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            setupSuccessfulProcessMock();

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.builder()
                        .command("echo", "test")
                        .build(); // No timeout

                ExecResult result = sandbox.exec(spec);

                assertThat(result.success()).isTrue();
                verify(mockProcess).waitFor(); // Should use the no-timeout version
                verify(mockProcess, never()).waitFor(anyLong(), any(TimeUnit.class));
            }
        }
    }

    @Nested
    @DisplayName("Error conditions and edge cases")
    class ErrorConditionTests {

        @Test
        @DisplayName("should handle process start failure")
        void exec_handlesProcessStartFailure() throws Exception {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenThrow(new IOException("Failed to start process"));
                    })) {
                ExecSpec spec = ExecSpec.of("nonexistent-command");

                assertThatThrownBy(() -> sandbox.exec(spec))
                        .isInstanceOf(IOException.class)
                        .hasMessage("Failed to start process");
            }
        }

        @Test
        @DisplayName("should handle interrupted exception")
        void exec_handlesInterruptedException() throws InterruptedException {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();

            when(mockProcess.waitFor()).thenThrow(new InterruptedException("Process interrupted"));

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.of("echo", "test");

                assertThatThrownBy(() -> sandbox.exec(spec))
                        .isInstanceOf(InterruptedException.class)
                        .hasMessage("Process interrupted");
            }
        }

        @Test
        @DisplayName("should reject execution after close")
        void exec_rejectsExecutionAfterClose() {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            sandbox.close();

            ExecSpec spec = ExecSpec.of("echo", "test");

            assertThatThrownBy(() -> sandbox.exec(spec))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Sandbox has been closed");
        }

        @Test
        @DisplayName("should ensure process cleanup in finally block")
        void exec_ensuresProcessCleanup() throws InterruptedException {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();

            when(mockProcess.waitFor()).thenThrow(new RuntimeException("Unexpected error"));
            when(mockProcess.isAlive()).thenReturn(true);

            try (MockedConstruction<ProcessBuilder> mocked = mockConstruction(ProcessBuilder.class,
                    (mock, context) -> {
                        when(mock.directory(any())).thenReturn(mock);
                        when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                        when(mock.environment()).thenReturn(new HashMap<>());
                        when(mock.start()).thenReturn(mockProcess);
                    })) {
                ExecSpec spec = ExecSpec.of("echo", "test");

                assertThatThrownBy(() -> sandbox.exec(spec))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessage("Unexpected error");

                // Verify cleanup happened even after exception
                verify(mockProcess).destroyForcibly();
            }
        }
    }

    @Nested
    @DisplayName("Directory management")
    class DirectoryManagementTests {

        @Test
        @DisplayName("should clean up directory on close")
        void close_cleansUpDirectory() throws IOException {
            Path workDir = tempDir.resolve("sandbox-work");
            sandbox = LocalSandbox.builder().workingDirectory(workDir).build();

            // Create some files in the directory
            Files.createDirectories(workDir.resolve("subdir"));
            Files.write(workDir.resolve("file1.txt"), "content".getBytes());
            Files.write(workDir.resolve("subdir/file2.txt"), "more content".getBytes());

            assertThat(Files.exists(workDir)).isTrue();
            assertThat(Files.list(workDir)).isNotEmpty();

            sandbox.close();

            assertThat(Files.exists(workDir)).isFalse();
            assertThat(sandbox.isClosed()).isTrue();
        }

        @Test
        @DisplayName("should handle close when directory doesn't exist")
        void close_handlesNonExistentDirectory() throws IOException {
            Path workDir = tempDir.resolve("nonexistent");
            sandbox = LocalSandbox.builder().workingDirectory(workDir).build();

            // Delete the directory manually
            Files.deleteIfExists(workDir);
            assertThat(Files.exists(workDir)).isFalse();

            // Should not throw exception
            assertThatCode(() -> sandbox.close()).doesNotThrowAnyException();
            assertThat(sandbox.isClosed()).isTrue();
        }

        @Test
        @DisplayName("should handle multiple calls to close gracefully")
        void close_handlesMultipleCalls() {
            sandbox = LocalSandbox.builder().workingDirectory(tempDir).build();
            sandbox.close();
            assertThat(sandbox.isClosed()).isTrue();

            // Second close should be safe
            assertThatCode(() -> sandbox.close()).doesNotThrowAnyException();
            assertThat(sandbox.isClosed()).isTrue();
        }

        @Test
        @DisplayName("should handle permission issues on close gracefully")
        void close_handlesPermissionIssuesGracefully() throws IOException {
            // Create a read-only directory
            Path workDir = tempDir.resolve("readonly");
            Files.createDirectory(workDir);
            Path file = workDir.resolve("file.txt");
            Files.write(file, "content".getBytes());

            // On POSIX systems, removing write permission from the directory prevents deletion of its contents
            workDir.toFile().setWritable(false);

            sandbox = LocalSandbox.builder().workingDirectory(workDir).build();

            // Close should log an error but not throw an exception
            assertThatCode(() -> sandbox.close()).doesNotThrowAnyException();
        }
    }

    // Helper method to setup successful process mock
    private void setupSuccessfulProcessMock() throws Exception {
        when(mockProcess.waitFor()).thenReturn(0);
        when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(mockProcess.exitValue()).thenReturn(0);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream("hello world\n".getBytes()));
        when(mockProcess.isAlive()).thenReturn(false);
    }
}