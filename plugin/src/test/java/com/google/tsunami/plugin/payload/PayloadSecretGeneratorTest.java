/*
 * Copyright 2021 Google LLC
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
package com.google.tsunami.plugin.payload;

import static com.google.common.truth.Truth.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PayloadSecretGenerator}. */
@RunWith(JUnit4.class)
public final class PayloadSecretGeneratorTest {
  private static final SecureRandom TEST_RNG =
      new SecureRandom() {
        @Override
        public void nextBytes(byte[] bytes) {
          Arrays.fill(bytes, (byte) 0xFF);
        }
      };

  @Test
  public void generate_always_generatesExpectedSecretString() {
    PayloadSecretGenerator secretGenerator = new PayloadSecretGenerator(TEST_RNG);

    assertThat(secretGenerator.generate(4)).isEqualTo("ffffffff");
  }
}
