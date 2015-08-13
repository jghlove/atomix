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

package net.kuujo.copycat.raft.server.state;

import net.kuujo.copycat.raft.ApplicationException;
import net.kuujo.copycat.raft.Operation;
import net.kuujo.copycat.raft.server.Commit;
import net.kuujo.copycat.raft.server.ScheduledTask;
import net.kuujo.copycat.raft.server.StateMachineExecutor;
import net.kuujo.copycat.util.concurrent.ComposableFuture;
import net.kuujo.copycat.util.concurrent.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Raft server state machine executor.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
class ServerStateMachineExecutor implements StateMachineExecutor {
  private final Context executor;
  private final ServerStateMachineContext context;
  private final List<ServerScheduledTask> tasks = new ArrayList<>();
  private final List<ServerScheduledTask> complete = new ArrayList<>();
  private final Map<Class, Function> operations = new HashMap<>();
  private Function allOperation;

  ServerStateMachineExecutor(Context context) {
    this.executor = context;
    this.context = new ServerStateMachineContext();
  }

  @Override
  public ServerStateMachineContext context() {
    return context;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends Operation<U>, U> CompletableFuture<U> execute(Commit<T> commit) {
    ComposableFuture<U> future = new ComposableFuture<>();
    executor.execute(() -> {
      context.update(commit.index(), commit.time());

      // Get the function registered for the operation. If no function is registered, attempt to
      // use a global function if available.
      Function function = operations.get(commit.type());
      if (function == null) {
        function = allOperation;
      }

      if (function == null) {
        future.completeExceptionally(new IllegalStateException("unknown state machine operation: " + commit.type()));
      } else {
        // Execute the operation. If the operation return value is a Future, await the result,
        // otherwise immediately complete the execution future.
        try {
          Object result = function.apply(commit);
          if (result instanceof CompletableFuture) {
            ((CompletableFuture<U>) result).whenCompleteAsync(future, executor);
          } else if (result instanceof Future) {
            future.complete(((Future<U>) result).get());
          } else {
            future.complete((U) result);
          }
        } catch (Exception e) {
          future.completeExceptionally(new ApplicationException("An application error occurred", e));
        }
      }
    });

    // We have to make sure to trigger scheduled callbacks in this thread in order to ensure
    // they're properly executed *immediately* after the above scheduled execution.
    // This means tasks are scheduled to be executed prior to the state machine time being set.
    tick(commit.time());
    return future;
  }

  /**
   * Executes scheduled callbacks based on the provided time.
   */
  void tick(Instant instant) {
    // Only create an iterator if there are actually tasks scheduled.
    if (!tasks.isEmpty()) {

      // Iterate through scheduled tasks until we reach a task that has not met its scheduled time.
      // The tasks list is sorted by time on insertion.
      Iterator<ServerScheduledTask> iterator = tasks.iterator();
      while (iterator.hasNext()) {
        ServerScheduledTask task = iterator.next();
        if (task.complete(instant)) {
          executor.execute(task::execute);
          complete.add(task);
          iterator.remove();
        } else {
          break;
        }
      }

      // Iterate through tasks that were completed and reschedule them.
      for (ServerScheduledTask task : complete) {
        task.reschedule();
      }
      complete.clear();
    }
  }

  @Override
  public CompletableFuture<Void> execute(Runnable callback) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    executor.execute(() -> {
      try {
        callback.run();
        future.complete(null);
      } catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public <T> CompletableFuture<T> execute(Callable<T> callback) {
    CompletableFuture<T> future = new CompletableFuture<>();
    executor.execute(() -> {
      try {
        future.complete(callback.call());
      } catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public ScheduledTask schedule(Duration delay, Runnable callback) {
    return new ServerScheduledTask(delay.toMillis(), callback).schedule();
  }

  @Override
  public ScheduledTask schedule(Duration initialDelay, Duration interval, Runnable callback) {
    return new ServerScheduledTask(initialDelay.toMillis(), interval.toMillis(), callback).schedule();
  }

  @Override
  public StateMachineExecutor register(Function<Commit<? extends Operation<?>>, ?> callback) {
    allOperation = callback;
    return this;
  }

  @Override
  public <T extends Operation<Void>> StateMachineExecutor register(Class<T> type, Consumer<Commit<T>> callback) {
    if (callback == null)
      throw new NullPointerException("callback cannot be null");
    operations.put(type, (Function<Commit<T>, Void>) commit -> {
      callback.accept(commit);
      return null;
    });
    return this;
  }

  @Override
  public <T extends Operation<U>, U> StateMachineExecutor register(Class<T> type, Function<Commit<T>, U> callback) {
    if (callback == null)
      throw new NullPointerException("callback cannot be null");
    operations.put(type, callback);
    return this;
  }

  /**
   * Scheduled task.
   */
  private class ServerScheduledTask implements ScheduledTask {
    private final long delay;
    private final long interval;
    private final Runnable callback;
    private long time;

    private ServerScheduledTask(long delay, Runnable callback) {
      this(delay, 0, callback);
    }

    private ServerScheduledTask(long delay, long interval, Runnable callback) {
      this.delay = delay;
      this.interval = interval;
      this.callback = callback;
      this.time = context.clock().instant().toEpochMilli() + delay;
    }

    /**
     * Schedules the task.
     */
    private ScheduledTask schedule() {
      // Perform binary search to insert the task at the appropriate position in the tasks list.
      if (tasks.isEmpty()) {
        tasks.add(this);
      } else {
        int l = 0;
        int u = tasks.size() - 1;
        int i;
        while (true) {
          i = (u + l) / 2;
          long t = tasks.get(i).time;
          if (t == time) {
            tasks.add(i, this);
            return this;
          } else if (t < time) {
            l = i + 1;
            if (l > u) {
              tasks.add(i + 1, this);
              return this;
            }
          } else {
            u = i - 1;
            if (l > u) {
              tasks.add(i, this);
              return this;
            }
          }
        }
      }
      return this;
    }

    /**
     * Reschedules the task.
     */
    private void reschedule() {
      if (interval > 0) {
        time = context.clock().instant().toEpochMilli() + delay;
        schedule();
      }
    }

    /**
     * Returns a boolean value indicating whether the task delay has been met.
     */
    private boolean complete(Instant instant) {
      return instant.toEpochMilli() >= time;
    }

    /**
     * Executes the task.
     */
    private synchronized void execute() {
      callback.run();
    }

    @Override
    public synchronized void cancel() {
      tasks.remove(this);
    }
  }

}
