/*
 * Copyright 2022 Google LLC
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
package com.google.tsunami.plugin;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.api.client.util.ExponentialBackOff;
import com.google.auto.value.AutoAnnotation;
import com.google.auto.value.AutoBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.multibindings.MapBinder;
import com.google.tsunami.common.server.LanguageServerCommand;
import com.google.tsunami.plugin.annotations.PluginInfo;
import io.grpc.Channel;
import io.grpc.Deadline;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;

/** A Guice module that loads all {@link RemoteVulnDetector RemoteVulnDetectors} at runtime. */
public final class RemoteVulnDetectorLoadingModule extends AbstractModule {
  private static final int MAX_MESSAGE_SIZE =
      10 * 1000 * 1000; // Max incoming gRPC message size 10MB.
  private static final int INITIAL_WAIT_TIME_MS = 200;
  private static final int MAX_WAIT_TIME_MS = 30000;
  private static final int WAIT_TIME_MULTIPLIER = 5;
  private static final int MAX_ATTEMPTS = 5;
  // Exponential delay attempts (>125 seconds before taking randomization factor into account):
  // ~200ms
  // ~1000ms
  // ~5000ms
  // ~25000ms
  // ~1250000ms
  private static final ExponentialBackOff BACKOFF =
      new ExponentialBackOff.Builder()
          .setInitialIntervalMillis(INITIAL_WAIT_TIME_MS)
          .setRandomizationFactor(0.1)
          .setMultiplier(WAIT_TIME_MULTIPLIER)
          .setMaxElapsedTimeMillis(MAX_WAIT_TIME_MS)
          .build();
  private final ImmutableList<LanguageServerCommand> availableServerPorts;

  public RemoteVulnDetectorLoadingModule(ImmutableList<LanguageServerCommand> serverPorts) {
    this.availableServerPorts = checkNotNull(serverPorts);
  }

  @Override
  protected void configure() {
    MapBinder<PluginDefinition, TsunamiPlugin> tsunamiPluginBinder =
        MapBinder.newMapBinder(binder(), PluginDefinition.class, TsunamiPlugin.class);
    availableServerPorts.forEach(
        command -> {
          var channel = getLanguageServerChannel(command);
          var deadline =
              command.deadlineRunSeconds() > 0
                  ? Deadline.after(command.deadlineRunSeconds(), SECONDS)
                  : null;
          tsunamiPluginBinder
              .addBinding(getRemoteVulnDetectorPluginDefinition(channel.hashCode()))
              .toInstance(new RemoteVulnDetectorImpl(channel, BACKOFF, MAX_ATTEMPTS, deadline));
        });
  }

  private Channel getLanguageServerChannel(LanguageServerCommand command) {
    if (Strings.isNullOrEmpty(command.serverCommand())) {
      return NettyChannelBuilder.forTarget(
              String.format("%s:%s", command.serverAddress(), command.port()))
          .negotiationType(NegotiationType.PLAINTEXT)
          .maxInboundMessageSize(MAX_MESSAGE_SIZE)
          .build();
    } else {
      // TODO(b/289462738): Support IPv6 loopback (::1) interface
      return NettyChannelBuilder.forTarget("127.0.0.1:" + command.port())
          .negotiationType(NegotiationType.PLAINTEXT)
          .maxInboundMessageSize(MAX_MESSAGE_SIZE)
          .build();
    }
  }

  // TODO(b/239095108): Change channelIds to something more meaningful to identify
  // RemoteVulnDetectors.
  @VisibleForTesting
  static PluginDefinition getRemoteVulnDetectorPluginDefinition(int channelId) {
    return PluginDefinition.forRemotePlugin(
        pluginInfoBuilder()
            .type(PluginType.REMOTE_VULN_DETECTION)
            .name("RemoteVulnDetector" + channelId)
            .description("Synthetic PluginInfo for RemoteVulnDetectors")
            .author("Tsunami")
            .version("0.0.1")
            .bootstrapModule(RemoteVulnDetectorBootstrapLoadingModule.class)
            .build());
  }

  /** Builder to build a {@link PluginInfo} annotation at runtime for RemoteVulnDetectors. */
  @AutoBuilder(callMethod = "pluginInfo")
  interface PluginInfoBuilder {
    PluginInfoBuilder type(PluginType type);

    PluginInfoBuilder name(String name);

    PluginInfoBuilder description(String description);

    PluginInfoBuilder author(String author);

    PluginInfoBuilder version(String version);

    PluginInfoBuilder bootstrapModule(Class<? extends PluginBootstrapModule> bootstrapModule);

    PluginInfo build();
  }

  // Used by {@link AutoBuilder}
  @SuppressWarnings("unused")
  @AutoAnnotation
  static PluginInfo pluginInfo(
      PluginType type,
      String name,
      String description,
      String author,
      String version,
      Class<? extends PluginBootstrapModule> bootstrapModule) {
    return new AutoAnnotation_RemoteVulnDetectorLoadingModule_pluginInfo(
        type, name, description, author, version, bootstrapModule);
  }

  static PluginInfoBuilder pluginInfoBuilder() {
    return new AutoBuilder_RemoteVulnDetectorLoadingModule_PluginInfoBuilder();
  }

  private static class RemoteVulnDetectorBootstrapLoadingModule extends PluginBootstrapModule {
    @Override
    protected void configurePlugin() {}
  }
}
