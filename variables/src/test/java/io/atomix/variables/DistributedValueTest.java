/*
 * Copyright 2015 the original author or authors.
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
package io.atomix.variables;

import org.testng.annotations.Test;

import io.atomix.copycat.client.CopycatClient;
import io.atomix.variables.state.ValueState;
import io.atomix.resource.ResourceStateMachine;

/**
 * Distributed atomic value test.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@Test
public class DistributedValueTest extends AbstractVariableTest {

  @Override
  protected ResourceStateMachine createStateMachine() {
    return new ValueState();
  }

  /**
   * Tests a set of atomic operations.
   */
  public void testAtomicSetGet() throws Throwable {
    createServers(3);
    CopycatClient client = createClient();
    DistributedValue<String> atomic = new DistributedValue<>(client);
    atomic.set("Hello world!").thenRun(() -> {
      atomic.get().thenAccept(value -> {
        threadAssertEquals(value, "Hello world!");
        resume();
      });
    });
    await();
  }

}
