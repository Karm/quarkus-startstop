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
package io.quarkus.ts.startstop;

import io.quarkus.ts.startstop.utils.Apps;
import io.quarkus.ts.startstop.utils.Commands;
import io.quarkus.ts.startstop.utils.Logs;
import io.quarkus.ts.startstop.utils.MvnCmds;
import io.quarkus.ts.startstop.utils.WebpageTester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static io.quarkus.ts.startstop.utils.Commands.cleanTarget;
import static io.quarkus.ts.startstop.utils.Commands.getBaseDir;
import static io.quarkus.ts.startstop.utils.Commands.getOpenedFDs;
import static io.quarkus.ts.startstop.utils.Commands.getRSSkB;
import static io.quarkus.ts.startstop.utils.Commands.parsePort;
import static io.quarkus.ts.startstop.utils.Commands.processStopper;
import static io.quarkus.ts.startstop.utils.Commands.runCommand;
import static io.quarkus.ts.startstop.utils.Commands.waitForTcpClosed;
import static io.quarkus.ts.startstop.utils.Logs.archiveLog;
import static io.quarkus.ts.startstop.utils.Logs.checkLog;
import static io.quarkus.ts.startstop.utils.Logs.getLogsDir;
import static io.quarkus.ts.startstop.utils.Logs.parseStartStopTimestamps;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Michal Karm Babacek <karm@redhat.com>
 */
public class StartStopTest {

    private static final Logger LOGGER = Logger.getLogger(StartStopTest.class.getName());

    public static final String BASE_DIR = getBaseDir();

    public void testRuntime(TestInfo testInfo, Apps app, MvnCmds mvnCmds) throws IOException, InterruptedException {
        LOGGER.info("Testing app: " + app.toString() + ", mode: " + mvnCmds.toString());

        Process pA = null;
        File buildLogA = null;
        File runLogA = null;
        File appDir = new File(BASE_DIR + File.separator + app.dir);

        try {
            // Cleanup
            cleanTarget(app);
            Files.createDirectories(Paths.get(appDir.getAbsolutePath() + File.separator + "logs"));

            // Build
            buildLogA = new File(appDir.getAbsolutePath() + File.separator + "logs" + File.separator + mvnCmds.name().toLowerCase() + "-build.log");
            ExecutorService buildService = Executors.newFixedThreadPool(1);
            buildService.submit(new Commands.ProcessRunner(appDir, buildLogA, mvnCmds.mvnCmds[0], 20));
            long buildStarts = System.currentTimeMillis();
            buildService.shutdown();
            buildService.awaitTermination(30, TimeUnit.MINUTES);
            long buildEnds = System.currentTimeMillis();

            assertTrue(buildLogA.exists());
            checkLog(testInfo.getTestClass().get().getCanonicalName(), testInfo.getTestMethod().get().getName(), app, mvnCmds, buildLogA);

            // Run
            LOGGER.info("Running...");
            runLogA = new File(appDir.getAbsolutePath() + File.separator + "logs" + File.separator + mvnCmds.name().toLowerCase() + "-run.log");
            pA = runCommand(mvnCmds.mvnCmds[1], appDir, runLogA);

            // Test web pages
            long timeToFirstOKRequest = WebpageTester.testWeb(app.urlContent.urlContent[0][0], 10, app.urlContent.urlContent[0][1], true);
            LOGGER.info("Testing web page content...");
            for (String[] urlContent : app.urlContent.urlContent) {
                WebpageTester.testWeb(urlContent[0], 5, urlContent[1], false);
            }

            LOGGER.info("Terminate and scan logs...");
            pA.getInputStream().available();

            long rssKb = getRSSkB(pA.pid());
            long openedFiles = getOpenedFDs(pA.pid());

            processStopper(pA, false);

            LOGGER.info("Gonna wait for ports closed...");
            // Release ports
            assertTrue(waitForTcpClosed("localhost", parsePort(app.urlContent.urlContent[0][0]), 60),
                    "Main port is still open");
            checkLog(testInfo.getTestClass().get().getCanonicalName(), testInfo.getTestMethod().get().getName(), app, mvnCmds, runLogA);

            float[] startedStopped = parseStartStopTimestamps(runLogA);

            Path measurementsLog = Paths.get(getLogsDir(testInfo.getTestClass().get().getCanonicalName()).toString(), "measurements.csv");
            Logs.logMeasurements(buildEnds - buildStarts, timeToFirstOKRequest,
                    (long) (startedStopped[0] * 1000), (long) (startedStopped[1] * 1000), rssKb, openedFiles, app, mvnCmds, measurementsLog);

        } finally {
            // Make sure processes are down even if there was an exception / failure
            if (pA != null) {
                processStopper(pA, true);
            }
            // Archive logs no matter what
            archiveLog(testInfo.getTestClass().get().getCanonicalName(), testInfo.getTestMethod().get().getName(), buildLogA);
            archiveLog(testInfo.getTestClass().get().getCanonicalName(), testInfo.getTestMethod().get().getName(), runLogA);
            cleanTarget(app);
        }
    }

    @Test
    public void jaxRsMinimalJVM(TestInfo testInfo) throws IOException, InterruptedException {
        testRuntime(testInfo, Apps.JAX_RS_MINIMAL, MvnCmds.JVM);
    }

    @Test
    public void jaxRsMinimalNative(TestInfo testInfo) throws IOException, InterruptedException {
        if (Commands.isThisWindows) {
            LOGGER.warning(testInfo.getTestMethod().get().getName() + " is skipped on Windows for the time being.");
            return;
        }
        testRuntime(testInfo, Apps.JAX_RS_MINIMAL, MvnCmds.NATIVE);
    }

    @Test
    public void fullMicroProfileJVM(TestInfo testInfo) throws IOException, InterruptedException {
        testRuntime(testInfo, Apps.FULL_MICROPROFILE, MvnCmds.JVM);
    }

    @Test
    public void fullMicroProfileNative(TestInfo testInfo) throws IOException, InterruptedException {
        if (Commands.isThisWindows) {
            LOGGER.warning(testInfo.getTestMethod().get().getName() + " is skipped on Windows for the time being.");
            return;
        }
        testRuntime(testInfo, Apps.FULL_MICROPROFILE, MvnCmds.NATIVE);
    }
}