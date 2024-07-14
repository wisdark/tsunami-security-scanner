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
package com.google.tsunami.common.server;

import com.google.auto.value.AutoValue;
import java.time.Duration;
import javax.annotation.Nullable;

/** Command to spawn a language server and associated command lines. */
@AutoValue
public abstract class LanguageServerCommand {
  public static LanguageServerCommand create(
      String serverCommand,
      @Nullable String serverAddress,
      String port,
      String logId,
      String outputDir,
      boolean trustAllSsl,
      Duration timeoutSeconds,
      String callbackAddress,
      Integer callbackPort,
      String pollingUri,
      int deadlineRunSeconds) {
    return new AutoValue_LanguageServerCommand(
        serverCommand,
        serverAddress,
        port,
        logId,
        outputDir,
        trustAllSsl,
        timeoutSeconds,
        callbackAddress,
        callbackPort,
        pollingUri,
        deadlineRunSeconds);
  }

  public abstract String serverCommand();

  public abstract String serverAddress();

  public abstract String port();

  public abstract String logId();

  public abstract String outputDir();

  public abstract boolean trustAllSslCert();

  public abstract Duration timeoutSeconds();

  public abstract String callbackAddress();

  public abstract Integer callbackPort();

  public abstract String pollingUri();

  public abstract int deadlineRunSeconds();
}
