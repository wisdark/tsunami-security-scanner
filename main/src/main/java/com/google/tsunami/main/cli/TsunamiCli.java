/*
 * Copyright 2020 Google LLC
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
package com.google.tsunami.main.cli;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.tsunami.common.data.NetworkEndpointUtils.forHostname;
import static com.google.tsunami.common.data.NetworkEndpointUtils.forIp;
import static com.google.tsunami.common.data.NetworkEndpointUtils.forIpAndHostname;
import static com.google.tsunami.common.data.NetworkServiceUtils.buildUriNetworkService;

import com.beust.jcommander.ParameterException;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.flogger.GoogleLogger;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.tsunami.common.cli.CliOptionsModule;
import com.google.tsunami.common.command.CommandExecutorModule;
import com.google.tsunami.common.config.ConfigLoader;
import com.google.tsunami.common.config.ConfigModule;
import com.google.tsunami.common.config.TsunamiConfig;
import com.google.tsunami.common.config.YamlConfigLoader;
import com.google.tsunami.common.io.archiving.GoogleCloudStorageArchiverModule;
import com.google.tsunami.common.net.http.HttpClientModule;
import com.google.tsunami.common.reflection.ClassGraphModule;
import com.google.tsunami.common.server.LanguageServerCommand;
import com.google.tsunami.common.time.SystemUtcClockModule;
import com.google.tsunami.main.cli.option.MainCliOptions;
import com.google.tsunami.main.cli.server.RemoteServerLoader;
import com.google.tsunami.main.cli.server.RemoteServerLoaderModule;
import com.google.tsunami.plugin.PluginExecutionModule;
import com.google.tsunami.plugin.PluginLoadingModule;
import com.google.tsunami.plugin.RemoteVulnDetectorLoadingModule;
import com.google.tsunami.plugin.payload.PayloadGeneratorModule;
import com.google.tsunami.proto.ScanResults;
import com.google.tsunami.proto.ScanStatus;
import com.google.tsunami.proto.ScanTarget;
import com.google.tsunami.workflow.DefaultScanningWorkflow;
import com.google.tsunami.workflow.ScanningWorkflowException;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Command line interface for the Tsunami Security Scanner. */
public final class TsunamiCli {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final DefaultScanningWorkflow scanningWorkflow;
  private final ScanResultsArchiver scanResultsArchiver;
  private final MainCliOptions mainCliOptions;
  private final RemoteServerLoader remoteServerLoader;

  @Inject
  TsunamiCli(
      DefaultScanningWorkflow scanningWorkflow,
      ScanResultsArchiver scanResultsArchiver,
      MainCliOptions mainCliOptions,
      RemoteServerLoader remoteServerLoader) {
    this.scanningWorkflow = checkNotNull(scanningWorkflow);
    this.scanResultsArchiver = checkNotNull(scanResultsArchiver);
    this.mainCliOptions = checkNotNull(mainCliOptions);
    this.remoteServerLoader = checkNotNull(remoteServerLoader);
  }

  public boolean run()
      throws ExecutionException, InterruptedException, ScanningWorkflowException, IOException {
    String logId = (mainCliOptions.logId == null) ? "" : (mainCliOptions.logId + ": ");
    // TODO(b/171405612): Find a way to print the log ID at every log line.
    logger.atInfo().log("%sTsunamiCli starting...", logId);

    ImmutableList<Process> languageServerProcesses = remoteServerLoader.runServerProcesses();
    ScanResults scanResults = scanningWorkflow.run(buildScanTarget());
    languageServerProcesses.forEach(Process::destroy);

    logger.atInfo().log("Tsunami scan finished, saving results.");
    saveResults(scanResults);

    if (hasSuccessfulResults(scanResults)) {
      logger.atInfo().log("TsunamiCli finished...");
      return true;
    } else {
      logger.atInfo().log(
          "Tsunami scan has failed status, message = %s.", scanResults.getStatusMessage());
      return false;
    }
  }

  private static boolean hasSuccessfulResults(ScanResults scanResults) {
    return scanResults.getScanStatus().equals(ScanStatus.SUCCEEDED)
        || scanResults.getScanStatus().equals(ScanStatus.PARTIALLY_SUCCEEDED);
  }

  private ScanTarget buildScanTarget() {
    ScanTarget.Builder scanTargetBuilder = ScanTarget.newBuilder();

    String ip = null;
    if (mainCliOptions.ipV4Target != null) {
      ip = mainCliOptions.ipV4Target;
    } else if (mainCliOptions.ipV6Target != null) {
      ip = mainCliOptions.ipV6Target;
    }
    if (ip != null && mainCliOptions.hostnameTarget != null) {
      scanTargetBuilder.setNetworkEndpoint(forIpAndHostname(ip, mainCliOptions.hostnameTarget));
    } else if (ip != null) {
      scanTargetBuilder.setNetworkEndpoint(forIp(ip));
    } else if (mainCliOptions.uriTarget != null) {
      scanTargetBuilder.setNetworkService(buildUriNetworkService(mainCliOptions.uriTarget));
    } else {
      scanTargetBuilder.setNetworkEndpoint(forHostname(mainCliOptions.hostnameTarget));
    }

    return scanTargetBuilder.build();
  }

  private void saveResults(ScanResults scanResults) throws IOException {
    scanResultsArchiver.archive(scanResults);
  }

  private static final class TsunamiCliModule extends AbstractModule {
    private final ScanResult classScanResult;
    private final String[] args;
    private final TsunamiConfig tsunamiConfig;

    TsunamiCliModule(ScanResult classScanResult, String[] args, TsunamiConfig tsunamiConfig) {
      this.classScanResult = checkNotNull(classScanResult);
      this.args = checkNotNull(args);
      this.tsunamiConfig = checkNotNull(tsunamiConfig);
    }

    @Override
    protected void configure() {
      // TODO(b/171405612): Find a way to use the log ID extracted by the CLI options.
      String logId = extractLogId(args);

      // TODO(b/241964583): Only use LanguageServerOptions to extract language server args.
      ImmutableList<LanguageServerCommand> commands =
          extractPluginServerArgs(args, logId, tsunamiConfig);

      install(new ClassGraphModule(classScanResult));
      install(new ConfigModule(classScanResult, tsunamiConfig));
      install(new CliOptionsModule(classScanResult, "TsunamiCli", args));
      install(new SystemUtcClockModule());
      install(new CommandExecutorModule());
      install(new HttpClientModule.Builder().setLogId(logId).build());
      install(new GoogleCloudStorageArchiverModule());
      install(new ScanResultsArchiverModule());
      install(new PluginExecutionModule());
      install(new PluginLoadingModule(classScanResult));
      install(new PayloadGeneratorModule(new SecureRandom()));
      install(new RemoteServerLoaderModule(commands));
      install(new RemoteVulnDetectorLoadingModule(commands));
    }
    
    private ImmutableList<LanguageServerCommand> extractPluginServerArgs(
        String[] args, String logId, TsunamiConfig tsunamiConfig) {
      List<LanguageServerCommand> commands = Lists.newArrayList();
      Boolean trustAllSslCertCli = extractCliTrustAllSslCert(args);
      var paths = extractCliPluginServerArgs(args, "--plugin-server-paths=");
      var ports = extractCliPluginServerArgs(args, "--plugin-server-ports=");
      var pythonServerAddress = extractPythonPluginServerAddress(args);
      var pythonServerPort = extractPythonPluginServerPort(args);
      if (paths.size() != ports.size()) {
        throw new ParameterException(
            String.format(
                "Number of plugin server paths must be equal to number of plugin server ports."
                    + " Paths: %s. Ports: %s.",
                paths.size(), ports.size()));
      }
      if (paths.isEmpty() && Strings.isNullOrEmpty(pythonServerAddress)) {
        return ImmutableList.of();
      }
      if (tsunamiConfig.getRawConfigData().isEmpty()) {
        for (int i = 0; i < paths.size(); ++i) {
          commands.add(
              LanguageServerCommand.create(
                  paths.get(i),
                  "",
                  ports.get(i),
                  logId,
                  extractOutputDir(args),
                  trustAllSslCertCli != null && trustAllSslCertCli.booleanValue(),
                  Duration.ZERO,
                  "",
                  0,
                  ""));
        }
        if (!Strings.isNullOrEmpty(pythonServerAddress)) {
          commands.add(
              LanguageServerCommand.create(
                  "",
                  pythonServerAddress,
                  pythonServerPort,
                  logId,
                  extractOutputDir(args),
                  trustAllSslCertCli != null && trustAllSslCertCli.booleanValue(),
                  Duration.ZERO,
                  "",
                  0,
                  ""));
        }
        return ImmutableList.copyOf(commands);
      } else {
        Object callbackConfig =
            ((Map) tsunamiConfig.getRawConfigData().get("plugin")).get("callbackserver");
        Object httpClientConfig =
            ((Map) ((Map) tsunamiConfig.getRawConfigData().get("common")).get("net")).get("http");
        boolean trustAllSslCertConfig =
            (boolean) ((Map) httpClientConfig).get("trust_all_certificates");
        for (int i = 0; i < paths.size(); ++i) {
          commands.add(
              LanguageServerCommand.create(
                  paths.get(i),
                  "",
                  ports.get(i),
                  logId,
                  extractOutputDir(args),
                  trustAllSslCertCli == null
                      ? trustAllSslCertConfig
                      : trustAllSslCertCli.booleanValue(),
                  Duration.ofSeconds((int) ((Map) httpClientConfig).get("connect_timeout_seconds")),
                  (String) ((Map) callbackConfig).get("callback_address"),
                  (Integer) ((Map) callbackConfig).get("callback_port"),
                  (String) ((Map) callbackConfig).get("polling_uri")));
        }
        if (!Strings.isNullOrEmpty(pythonServerAddress)) {
          commands.add(
              LanguageServerCommand.create(
                  "",
                  pythonServerAddress,
                  pythonServerPort,
                  logId,
                  extractOutputDir(args),
                  trustAllSslCertCli == null
                      ? trustAllSslCertConfig
                      : trustAllSslCertCli.booleanValue(),
                  Duration.ofSeconds((int) ((Map) httpClientConfig).get("connect_timeout_seconds")),
                  (String) ((Map) callbackConfig).get("callback_address"),
                  (Integer) ((Map) callbackConfig).get("callback_port"),
                  (String) ((Map) callbackConfig).get("polling_uri")));
        }
        return ImmutableList.copyOf(commands);
      }
    }

    @Nullable
    private Boolean extractCliTrustAllSslCert(String[] args) {
      for (String arg : args) {
        if (arg.startsWith("--http-client-trust-all-certificates")) {
          if (arg.contains("=")) {
            return Boolean.valueOf(Iterables.get(Splitter.on('=').split(arg), 1));
          } else {
            return true;
          }
        }
      }
      return null;
    }

    @Nullable
    private String extractPythonPluginServerAddress(String[] args) {
      for (String arg : args) {
        if (arg.startsWith("--python-plugin-server-address")) {
          if (arg.contains("=")) {
            return Iterables.get(Splitter.on('=').split(arg), 1);
          } else {
            return null;
          }
        }
      }
      return null;
    }

    @Nullable
    private String extractPythonPluginServerPort(String[] args) {
      for (String arg : args) {
        if (arg.startsWith("--python-plugin-server-port")) {
          if (arg.contains("=")) {
            return Iterables.get(Splitter.on('=').split(arg), 1);
          } else {
            return null;
          }
        }
      }
      return null;
    }

    private String extractOutputDir(String[] args) {
      for (String arg : args) {
        if (arg.startsWith("--scan-results-local-output-filename=")) {
          String filename = Iterables.get(Splitter.on('=').split(arg), 1) + ": ";
          return Path.of(filename).getParent().toString();
        }
      }
      return "";
    }

    private ImmutableList<String> extractCliPluginServerArgs(String[] args, String flag) {
      for (String arg : args) {
        if (arg.startsWith(flag)) {
          var count = Iterables.get(Splitter.on('=').split(arg), 1);
          return ImmutableList.copyOf(Splitter.on(',').split(count));
        }
      }
      return ImmutableList.of();
    }

    private String extractLogId(String[] args) {
      // TODO(b/171405612): Use the Flag class instead of manual parsing.
      for (String arg : args) {
        if (arg.startsWith("--log-id=")) {
          return Iterables.get(Splitter.on('=').split(arg), 1) + ": ";
        }
      }
      return "";
    }
  }

  public static void main(String[] args) {
    Stopwatch stopwatch = Stopwatch.createStarted();

    TsunamiConfig tsunamiConfig = loadConfig();

    try (ScanResult scanResult =
        new ClassGraph()
            .enableAllInfo()
            .blacklistPackages("com.google.tsunami.plugin.testing")
            .scan()) {
      logger.atInfo().log("Full classpath scan took %s", stopwatch);

      Injector injector =
          Guice.createInjector(new TsunamiCliModule(scanResult, args, tsunamiConfig));

      // Exit with non-zero code if scan failed.
      if (!injector.getInstance(TsunamiCli.class).run()) {
        System.exit(1);
      }
      logger.atInfo().log("Full Tsunami scan took %s.", stopwatch.stop());
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("Exiting due to workflow execution exceptions.");
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.exit(1);
    }
  }

  private static TsunamiConfig loadConfig() {
    try (ScanResult scanResult = new ClassGraph().enableAllInfo().scan()) {
      ConfigLoader configLoader;
      Optional<String> loaderClass = TsunamiConfig.getSystemProperty("tsunami.config.loader");
      if (loaderClass.isPresent()
          && scanResult.getAllClassesAsMap().containsKey(loaderClass.get())) {
        configLoader =
            scanResult
                .getClassInfo(loaderClass.get())
                .loadClass(ConfigLoader.class)
                .getConstructor()
                .newInstance();
      } else {
        configLoader = new YamlConfigLoader();
      }

      return configLoader.loadConfig();
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Error loading config.", e);
    }
  }
}
