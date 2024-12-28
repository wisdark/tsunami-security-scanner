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
package com.google.tsunami.common.command;

import com.google.inject.AbstractModule;
import com.google.tsunami.common.concurrent.ThreadPoolModule;

/** Installs dependencies used by {@link CommandExecutor}. */
public class CommandExecutorModule extends AbstractModule {

  @Override
  protected void configure() {
    install(
        new ThreadPoolModule.Builder()
            .setName("CommandExecutor")
            .setCoreSize(4)
            .setMaxSize(8)
            .setQueueCapacity(32)
            .setDaemon(true)
            .setPriority(Thread.NORM_PRIORITY)
            .setAnnotation(CommandExecutionThreadPool.class)
            .build());
  }
}
