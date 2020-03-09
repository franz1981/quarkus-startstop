/*
 * Copyright (c) 2020 Contributors to the Quarkus StartStop project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.quarkus.ts.startstop.utils;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Scanner;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.quarkus.ts.startstop.StartStopTest.BASE_DIR;
import static io.quarkus.ts.startstop.utils.Commands.isThisWindows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Michal Karm Babacek <karm@redhat.com>
 */
public class Logs {
    private static final Logger LOGGER = Logger.getLogger(Logs.class.getName());

    private static final Pattern startedPattern = Pattern.compile(".* started in ([0-9\\.]+)s.*", Pattern.DOTALL);
    private static final Pattern stoppedPattern = Pattern.compile(".* stopped in ([0-9\\.]+)s.*", Pattern.DOTALL);
    /*
     Due to console colouring, Windows has control characters in the sequence.
     So "1.778s" in "started in 1.778s." becomes  "[38;5;188m1.778".
     e.g.
     //started in [38;5;188m1.228[39ms.
     //stopped in [38;5;188m0.024[39ms[39m[38;5;203m[39m[38;5;227m

     Although when run from Jenkins service account; those symbols might not be present
     depending on whether you checked AllowInteractingWithDesktop.
     // TODO to make it smoother?
     */
    private static final Pattern startedPatternControlSymbols = Pattern.compile(".* started in .*188m([0-9\\.]+).*", Pattern.DOTALL);
    private static final Pattern stoppedPatternControlSymbols = Pattern.compile(".* stopped in .*188m([0-9\\.]+).*", Pattern.DOTALL);

    public static final long SKIP = -1L;

    // TODO: How about WARNING? Other unwanted messages?
    public static void checkLog(String testClass, String testMethod, Apps app, MvnCmds cmd, File log) throws FileNotFoundException {
        try (Scanner sc = new Scanner(log)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                boolean error = line.matches("(?i:.*ERROR.*)");
                boolean whiteListed = false;
                if (error) {
                    for (String w : app.whitelist.errs) {
                        if (line.contains(w)) {
                            whiteListed = true;
                            LOGGER.info(cmd.name() + "log for " + testMethod + " contains whitelisted error: `" + line + "'");
                            break;
                        }
                    }
                }
                assertFalse(error && !whiteListed, cmd.name() + " log should not contain `ERROR' lines that are not whitelisted. " +
                        "See testsuite" + File.separator + "target" + File.separator + "archived-logs" +
                        File.separator + testClass + File.separator + testMethod + File.separator + log.getName());
            }
        }
    }

    public static void checkThreshold(Apps app, MvnCmds cmd, long rssKb, long timeToFirstOKRequest, long timeToReloadedOKRequest) {
        String propPrefix = isThisWindows ? "windows" : "linux";
        if (cmd == MvnCmds.JVM) {
            propPrefix += ".jvm";
        } else if (cmd == MvnCmds.NATIVE) {
            propPrefix += ".native";
        } else if (cmd == MvnCmds.DEV) {
            propPrefix += ".dev";
        } else if (cmd == MvnCmds.GENERATOR) {
            propPrefix += ".generated.dev";
        } else {
            throw new IllegalArgumentException("Unexpected mode. Check MvnCmds.java.");
        }
        if (timeToFirstOKRequest != SKIP) {
            long timeToFirstOKRequestThresholdMs = app.thresholdProperties.get(propPrefix + ".time.to.first.ok.request.threshold.ms");
            assertTrue(timeToFirstOKRequest <= timeToFirstOKRequestThresholdMs,
                    "Application " + app + " in " + cmd + " mode took " + timeToFirstOKRequest
                            + " ms to get the first OK request, which is over " +
                            timeToFirstOKRequestThresholdMs + " ms threshold.");
        }
        if (rssKb != SKIP) {
            long rssThresholdKb = app.thresholdProperties.get(propPrefix + ".RSS.threshold.kB");
            assertTrue(rssKb <= rssThresholdKb,
                    "Application " + app + " in " + cmd + " consumed " + rssKb + " kB, which is over " +
                            rssThresholdKb + " kB threshold.");
        }
        if (timeToReloadedOKRequest != SKIP) {
            long timeToReloadedOKRequestThresholdMs = app.thresholdProperties.get(propPrefix + ".time.to.reload.threshold.ms");
            assertTrue(timeToReloadedOKRequest <= timeToReloadedOKRequestThresholdMs,
                    "Application " + app + " in " + cmd + " mode took " + timeToReloadedOKRequest
                            + " ms to get the first OK request after dev mode reload, which is over " +
                            timeToReloadedOKRequestThresholdMs + " ms threshold.");
        }
    }

    public static void archiveLog(String testClass, String testMethod, File log) throws IOException {
        if (log == null || !log.exists()) {
            LOGGER.severe("log must be a valid, existing file. Skipping operation.");
            return;
        }
        if (StringUtils.isBlank(testClass)) {
            throw new IllegalArgumentException("testClass must not be blank");
        }
        if (StringUtils.isBlank(testMethod)) {
            throw new IllegalArgumentException("testMethod must not be blank");
        }
        Path destDir = getLogsDir(testClass, testMethod);
        Files.createDirectories(destDir);
        String filename = log.getName();
        Files.copy(log.toPath(), Paths.get(destDir.toString(), filename));
    }

    public static Path getLogsDir(String testClass, String testMethod) throws IOException {
        Path destDir = new File(getLogsDir(testClass).toString() + File.separator + testMethod).toPath();
        Files.createDirectories(destDir);
        return destDir;
    }

    public static Path getLogsDir(String testClass) throws IOException {
        Path destDir = new File(BASE_DIR + File.separator + "testsuite" + File.separator + "target" +
                File.separator + "archived-logs" + File.separator + testClass).toPath();
        Files.createDirectories(destDir);
        return destDir;
    }

    public static void logMeasurements(LogBuilder.Log log, Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.write(path, (log.header + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        }
        Files.write(path, (log.line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        LOGGER.info("\n" + log.header + "\n" + log.line);
    }

    public static float[] parseStartStopTimestamps(File log) throws FileNotFoundException {
        float[] startedStopped = new float[]{-1f, -1f};
        try (Scanner sc = new Scanner(log)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();

                Matcher m = startedPatternControlSymbols.matcher(line);
                if (startedStopped[0] == -1f && m.matches()) {
                    startedStopped[0] = Float.parseFloat(m.group(1));
                    continue;
                }

                m = startedPattern.matcher(line);
                if (startedStopped[0] == -1f && m.matches()) {
                    startedStopped[0] = Float.parseFloat(m.group(1));
                    continue;
                }

                m = stoppedPatternControlSymbols.matcher(line);
                if (startedStopped[1] == -1f && m.matches()) {
                    startedStopped[1] = Float.parseFloat(m.group(1));
                    continue;
                }

                m = stoppedPattern.matcher(line);
                if (startedStopped[1] == -1f && m.matches()) {
                    startedStopped[1] = Float.parseFloat(m.group(1));
                }
            }
        }
        if (startedStopped[0] == -1f) {
            LOGGER.severe("Parsing start time from log failed. " +
                    "Might not be the right time to call this method. The process might have ben killed before it wrote to log." +
                    "Find " + log.getName() + " in your target dir.");
        }
        if (startedStopped[1] == -1f) {
            LOGGER.severe("Parsing stop time from log failed. " +
                    "Might not be the right time to call this method. The process might have been killed before it wrote to log." +
                    "Find " + log.getName() + " in your target dir.");
        }
        return startedStopped;
    }
}