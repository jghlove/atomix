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
package net.kuujo.copycat.cluster;

import net.kuujo.copycat.io.serializer.CopycatSerializer;
import net.kuujo.copycat.util.ExecutionContext;

/**
 * Abstract local member.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class AbstractLocalMember extends AbstractMember implements ManagedLocalMember {

  protected AbstractLocalMember(Info info, CopycatSerializer serializer, ExecutionContext context) {
    super(info, serializer, context);
  }

  /**
   * Abstract local member builder.
   */
  public static abstract class Builder<BUILDER extends Builder<BUILDER, TYPE>, TYPE extends AbstractLocalMember> extends AbstractMember.Builder<BUILDER, TYPE> {
  }

}
