/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.util.concurrency;

import com.intellij.diagnostic.ThreadDumper;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.TimeoutUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import junit.framework.TestCase;
import org.jetbrains.ide.PooledThreadExecutor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class BoundedTaskExecutorTest extends TestCase {
  @Override
  protected void tearDown() throws Exception {
    awaitAppPoolQuiescence();
    super.tearDown();
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    awaitAppPoolQuiescence();
  }

  private static void awaitAppPoolQuiescence() {
    long start = System.currentTimeMillis();
    while (true) {
      List<Thread> alive = Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith(AppScheduledExecutorService.POOLED_THREAD_PREFIX))
        .filter(thread -> thread.getState() == Thread.State.RUNNABLE)
        .filter(thread -> thread.getStackTrace().length != 0) // there can be RUNNABLE zombies with empty stacktrace
        .collect(Collectors.toList());

      long finish = System.currentTimeMillis();
      if (alive.isEmpty()) break;
      if (finish-start > 10000) {
        System.err.println(ThreadDumper.dumpThreadsToString());
        throw new RuntimeException(alive.size() +" threads are still alive: "+alive);
      }
    }
  }

  public void testReallyBound() throws InterruptedException, ExecutionException {
    for (int maxTasks=1; maxTasks<5;maxTasks++) {
      System.out.println("maxTasks = " + maxTasks);
      ExecutorService backendExecutor = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory("maxTasks = " + maxTasks));
      BoundedTaskExecutor executor = new BoundedTaskExecutor(backendExecutor, maxTasks);
      AtomicInteger running = new AtomicInteger();
      AtomicInteger max = new AtomicInteger();
      AtomicInteger executed = new AtomicInteger();
      int N = 10000;
      for (int i = 0; i < N; i++) {
        executor.execute(() -> {
          int r = running.incrementAndGet();
          try {
            TimeoutUtil.sleep(1);
            max.accumulateAndGet(r, Math::max);
            executed.incrementAndGet();
          }
          finally {
            running.decrementAndGet();
          }
        });
      }
      UIUtil.invokeAndWaitIfNeeded((Runnable)() -> {
        try {
          executor.waitAllTasksExecuted(5, TimeUnit.MINUTES);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });

      assertEquals(0, executor.shutdownNow().size());
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      backendExecutor.shutdownNow();
      assertTrue(backendExecutor.awaitTermination(100, TimeUnit.SECONDS));
      assertEquals(maxTasks, max.get());
      assertEquals(N, executed.get());
    }
  }

  public void testCallableReallyReturnsValue() throws Exception{
    ExecutorService backendExecutor = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory(getName()));
    BoundedTaskExecutor executor = new BoundedTaskExecutor(backendExecutor, 1);

    Future<Integer> f1 = executor.submit(() -> 42);
    Integer result = f1.get();
    assertEquals(42, result.intValue());
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    backendExecutor.shutdownNow();
    assertTrue(backendExecutor.awaitTermination(100, TimeUnit.SECONDS));
  }

  public void testEarlyCancelPreventsRunning() throws ExecutionException, InterruptedException {
    AtomicBoolean run = new AtomicBoolean();
    ExecutorService backendExecutor = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory(getName()));
    BoundedTaskExecutor executor = new BoundedTaskExecutor(backendExecutor, 1);

    int delay = 1000;
    Future<?> s1 = executor.submit(() -> TimeoutUtil.sleep(delay));
    Future<Integer> f1 = executor.submit(() -> {
      run.set(true);
      return 42;
    });
    f1.cancel(false);
    s1.get();
    assertTrue(f1.isDone());
    assertTrue(f1.isCancelled());
    assertFalse(run.get());
    assertTrue(s1.isDone());
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    backendExecutor.shutdownNow();
    assertTrue(backendExecutor.awaitTermination(100, TimeUnit.SECONDS));
  }

  public void testStressWhenSomeTasksCallOtherTasksGet() throws ExecutionException, InterruptedException {
    ExecutorService backendExecutor = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory(getName()));
    for (int maxSimultaneousTasks = 1; maxSimultaneousTasks<20; maxSimultaneousTasks++) {
      final Disposable myDisposable = Disposer.newDisposable();
      BoundedTaskExecutor executor = new BoundedTaskExecutor(backendExecutor, maxSimultaneousTasks, myDisposable);
      AtomicInteger running = new AtomicInteger();
      AtomicInteger maxThreads = new AtomicInteger();

      try {
        int N = 5000;
        Future[] futures = new Future[N];
        Random random = new Random();
        for (int i = 0; i < N; i++) {
          final int finalI = i;
          final int finalMaxSimultaneousTasks = maxSimultaneousTasks;
          futures[i] = executor.submit(() -> {
            maxThreads.accumulateAndGet(running.incrementAndGet(), Math::max);

            try {
              int r = random.nextInt(finalMaxSimultaneousTasks);
              int prev = finalI - r;
              if (prev < finalI && prev >= 0) {
                try {
                  futures[prev].get();
                }
                catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
              TimeoutUtil.sleep(r);
            }
            finally {
              running.decrementAndGet();
            }
          });
        }
        UIUtil.invokeAndWaitIfNeeded((Runnable)() -> {
          try {
            executor.waitAllTasksExecuted(5, TimeUnit.MINUTES);
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
        for (Future future : futures) {
          assertTrue(future.isDone());
        }
      }
      finally {
        Disposer.dispose(myDisposable);
        assertTrue(executor.isShutdown());
      }

      assertTrue("Max threads was: "+maxThreads+" but bound was: "+maxSimultaneousTasks, maxThreads.get() <= maxSimultaneousTasks);
    }
    backendExecutor.shutdownNow();
    assertTrue(backendExecutor.awaitTermination(100, TimeUnit.SECONDS));
  }

  public void testSequentialSubmitsMustExecuteSequentially() throws ExecutionException, InterruptedException {
    ExecutorService backendExecutor = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory(getName()));
    BoundedTaskExecutor executor = new BoundedTaskExecutor(backendExecutor, 1);
    int N = 100000;
    StringBuffer log = new StringBuffer(N*4);
    StringBuilder expected = new StringBuilder(N * 4);

    Future[] futures = new Future[N];
    for (int i = 0; i < N; i++) {
      String text = i + " ";
      futures[i] = executor.submit(() -> log.append(text));
    }
    for (int i = 0; i < N; i++) {
      expected.append(i).append(" ");
      futures[i].get();
    }

    String logs = log.toString();
    assertEquals(expected.toString(), logs);
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    backendExecutor.shutdownNow();
    assertTrue(backendExecutor.awaitTermination(100, TimeUnit.SECONDS));
  }

  public void testStressForHorribleABAProblemWhenFirstThreadFinishesTaskAndIsAboutToDecrementCountAndSecondThreadIncrementsCounterToTwoThenSkipsExecutionThenDecrementsItBackAndTheFirstThreadFinishedDecrementingSuccessfully() throws Exception {
    ExecutorService backendExecutor = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory(getName()));
    int maxSimultaneousTasks = 1;
    final Disposable myDisposable = Disposer.newDisposable();
    BoundedTaskExecutor executor = new BoundedTaskExecutor(backendExecutor, maxSimultaneousTasks, myDisposable);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxThreads = new AtomicInteger();

    try {
      int N = 100000;
      for (int i = 0; i < N; i++) {
        final int finalI = i;
        Future<?> future = executor.submit(new Runnable() {
          @Override
          public void run() {
            maxThreads.accumulateAndGet(running.incrementAndGet(), Math::max);

            try {
              if (finalI % 100 == 0) {
                TimeoutUtil.sleep(1);
              }
            }
            finally {
              running.decrementAndGet();
            }
          }

          @Override
          public String toString() {
            return "iter " + finalI;
          }
        });
        CountDownLatch waitCompleted = new CountDownLatch(1);
        CountDownLatch waitStarted = new CountDownLatch(1);
        UIUtil.invokeLaterIfNeeded(() -> {
          try {
            waitStarted.countDown();
            executor.waitAllTasksExecuted(1, TimeUnit.MINUTES);
            waitCompleted.countDown();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
        waitStarted.await();
        executor.execute(new Runnable() {
          @Override
          public void run() {
            maxThreads.accumulateAndGet(running.incrementAndGet(), Math::max);

            try {
              Thread.yield();
            }
            finally {
              running.decrementAndGet();
            }
          }

          @Override
          public String toString() {
            return "check for " + finalI;
          }
        });
        //TimeoutUtil.sleep(1);
        executor.execute(new Runnable() {
          @Override
          public void run() {
            maxThreads.accumulateAndGet(running.incrementAndGet(), Math::max);

            try {
              Thread.yield();
            }
            finally {
              running.decrementAndGet();
            }
          }

          @Override
          public String toString() {
            return "check 2 for " + finalI;
          }
        });
        assertTrue(waitCompleted.await(1, TimeUnit.MINUTES));
        assertTrue(future.isDone());
      }
      UIUtil.invokeAndWaitIfNeeded((Runnable)() -> {
        try {
          executor.waitAllTasksExecuted(1, TimeUnit.MINUTES);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }
    finally {
      Disposer.dispose(myDisposable);
      assertTrue(executor.isShutdown());
    }

    assertTrue("Max threads was: "+maxThreads+" but bound was 1", maxThreads.get() == 1);
    backendExecutor.shutdownNow();
    assertTrue(backendExecutor.awaitTermination(1, TimeUnit.MINUTES));
  }

  public void testShutdownNowMustCancel() throws ExecutionException, InterruptedException {
    ExecutorService executor = new BoundedTaskExecutor(PooledThreadExecutor.INSTANCE, 1);
    int N = 100000;
    StringBuffer log = new StringBuffer(N*4);

    Future[] futures = new Future[N];
    for (int i = 0; i < N; i++) {
      futures[i] = executor.submit(() -> log.append(" "));
    }
    List<Runnable> runnables = executor.shutdownNow();
    assertTrue(executor.isShutdown());

    Thread.sleep(1000); // wait for a rare chance of a task executing right now
    assertEquals(N - log.length(), runnables.size());

    try {
      executor.submit(EmptyRunnable.getInstance());
      fail("Must reject");
    }
    catch (RejectedExecutionException ignored) {
    }
    try {
      executor.execute(EmptyRunnable.getInstance());
      fail("Must reject");
    }
    catch (RejectedExecutionException ignored) {
    }

    for (int i = 0; i < log.length(); i++) {
      assertFalse(futures[i].isCancelled());
      assertTrue(futures[i].isDone());
    }
    for (int i = log.length(); i < N; i++) {
      assertTrue(futures[i].isCancelled());
      assertTrue(futures[i].isDone());
    }

    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
  }

  public void testShutdownMustDisableSubmit() throws ExecutionException, InterruptedException {
    ExecutorService executor = new BoundedTaskExecutor(PooledThreadExecutor.INSTANCE, 1);
    int N = 100000;
    StringBuffer log = new StringBuffer(N*4);

    Future[] futures = new Future[N];
    for (int i = 0; i < N; i++) {
      futures[i] = executor.submit(() -> log.append(" "));
    }
    executor.shutdown();
    assertTrue(executor.isShutdown());

    try {
      executor.submit(EmptyRunnable.getInstance());
      fail("Must reject");
    }
    catch (RejectedExecutionException ignored) {
    }
    try {
      executor.execute(EmptyRunnable.getInstance());
      fail("Must reject");
    }
    catch (RejectedExecutionException ignored) {
    }

    for (int i = 0; i < N; i++) {
      assertFalse(futures[i].isCancelled());
      futures[i].get();
    }

    String logs = log.toString();
    assertEquals(StringUtil.repeat(" ",N), logs);
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
  }

  public void testNoExtraThreadsAreEverCreated() throws ExecutionException, InterruptedException {
    for (int nMaxThreads=1; nMaxThreads<10; nMaxThreads++) {
      System.out.println("nMaxThreads = " + nMaxThreads);
      ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor(nMaxThreads);
      int N = 1000000;
      Set<Thread> workers = ContainerUtil.newConcurrentSet();

      CountDownLatch allStarted = new CountDownLatch(1);
      List<Future> saturate = ContainerUtil.map(Collections.nCopies(nMaxThreads, null), o -> executor.submit(new Runnable() {
        @Override
        public void run() {
          try {
            allStarted.await();
          }
          catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }

        @Override
        public String toString() {
          return "warmup";
        }
      }));


      List<Future> futures = new ArrayList<>(N);
      for (int i = 0; i < N; i++) {
        final int finalI = i;
        futures.add(executor.submit(new Runnable() {
          @Override
          public void run() {
            workers.add(Thread.currentThread());
          }

          @Override
          public String toString() {
            return "Runnable test "+finalI;
          }
        }));
        //System.out.println("i = " + i+" submitted");
      }

      allStarted.countDown();
      saturate.forEach(BoundedTaskExecutorTest::doGet);
      futures.forEach(BoundedTaskExecutorTest::doGet);

      //System.out.println("workers.size() = " + workers.size());
      assertTrue("Must create no more than "+nMaxThreads+" workers but got: "+workers,
                 workers.size() <= nMaxThreads);
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }
  }

  private static Object doGet(Future future) {
    try {
      return future.get();
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void testAwaitTerminationDoesWait() throws InterruptedException {
    ExecutorService executor = new BoundedTaskExecutor(PooledThreadExecutor.INSTANCE, 1);
    int N = 100000;
    StringBuffer log = new StringBuffer(N*4);

    Future[] futures = new Future[N];
    for (int i = 0; i < N; i++) {
      futures[i] = executor.submit(() -> log.append(" "));
    }
    executor.shutdown();
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

    String logs = log.toString();
    assertEquals(StringUtil.repeat(" ",N), logs);
    for (Future future : futures) {
      assertTrue(future.isDone());
      assertTrue(!future.isCancelled());
    }
  }

  public void testAwaitTerminationDoesNotCompletePrematurely() throws InterruptedException {
    ExecutorService executor2 = new BoundedTaskExecutor(PooledThreadExecutor.INSTANCE, 1);
    Future<?> future = executor2.submit(() -> TimeoutUtil.sleep(10000));
    executor2.shutdown();
    assertFalse(executor2.awaitTermination(1, TimeUnit.SECONDS));
    assertFalse(future.isDone());
    assertFalse(future.isCancelled());
    assertTrue(executor2.awaitTermination(10, TimeUnit.SECONDS));
    assertTrue(future.isDone());
    assertFalse(future.isCancelled());
  }
}
