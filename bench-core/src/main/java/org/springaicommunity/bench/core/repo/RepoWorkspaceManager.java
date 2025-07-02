package org.springaicommunity.bench.core.repo;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.springaicommunity.bench.core.spec.RepoSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class RepoWorkspaceManager {

    private final GitHub github;

    public RepoWorkspaceManager(GitHub github) {
        this.github = github;
    }

    /** Clone repo@ref into a temp dir; throws IOException on failure. */
    public Workspace checkout(RepoSpec spec, Duration cloneTimeout) throws IOException, InterruptedException {
        GHRepository gh = github.getRepository(spec.owner() + "/" + spec.name());
        String cloneUrl = gh.getHttpTransportUrl();

        Path workspace = Files.createTempDirectory("bench-workspace-");
        ProcessBuilder pb;

        if (looksLikeSha(spec.ref())) {
            // clone, then checkout SHA
            pb = new ProcessBuilder("git", "clone", cloneUrl, workspace.toString());
            run(pb, cloneTimeout, "git clone");
            pb = new ProcessBuilder("git", "-C", workspace.toString(), "checkout", spec.ref());
            run(pb, cloneTimeout, "git checkout");
        } else {
            pb = new ProcessBuilder("git", "clone", "--depth", "1",
                    "--branch", spec.ref(), cloneUrl, workspace.toString());
            run(pb, cloneTimeout, "git clone");
        }
        return new Workspace(workspace);
    }

    /* ------------------------------------------------------------------ */
    private static void run(ProcessBuilder pb, Duration timeout, String step)
            throws IOException, InterruptedException {
        Process p = pb.redirectErrorStream(true).start();
        if (!p.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                || p.exitValue() != 0) {
            throw new IOException(step + " failed (exit=" + p.exitValue() + ")");
        }
    }

    private static boolean looksLikeSha(String s) { return s.matches("[0-9a-fA-F]{7,40}"); }
}
