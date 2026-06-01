/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.util.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.security.auth.Subject;

import org.apache.hadoop.security.authentication.util.SubjectUtil;
import org.apache.hadoop.util.Daemon;
import org.junit.jupiter.api.Test;

public class TestSubjectPropagation {

  private Subject childSubject = null;

  @Test
  public void testSubjectInheritingThreadOverride() {
    Subject parentSubject = new Subject();
    childSubject = null;

    SubjectUtil.callAs(parentSubject, new Callable<Void>() {
      public Void call() throws InterruptedException {
        SubjectInheritingThread t = new SubjectInheritingThread() {
          @Override
          public void work() {
            childSubject = SubjectUtil.current();
          }
        };
        t.start();
        t.join(1000);
        return (Void) null;
      }
    });

    assertEquals(parentSubject, childSubject);
  }

  @Test
  public void testSubjectInheritingThreadRunnable() {
    Subject parentSubject = new Subject();
    childSubject = null;

    SubjectUtil.callAs(parentSubject, new Callable<Void>() {
      public Void call() throws InterruptedException {
        Runnable r = new Runnable() {
          @Override
          public void run() {
            childSubject = SubjectUtil.current();
          }
        };

        SubjectInheritingThread t = new SubjectInheritingThread(r);
        t.start();
        t.join(1000);
        return (Void) null;
      }
    });

    assertEquals(parentSubject, childSubject);
  }

  @Test
  public void testDaemonOverride() {
    Subject parentSubject = new Subject();
    childSubject = null;

    SubjectUtil.callAs(parentSubject, new Callable<Void>() {
      public Void call() throws InterruptedException {
        Daemon t = new Daemon() {
          @Override
          public void work() {
            childSubject = SubjectUtil.current();
          }
        };
        t.start();
        t.join(1000);
        return (Void) null;
      }
    });

    assertEquals(parentSubject, childSubject);
  }

  @Test
  public void testDaemonRunnable() {
    Subject parentSubject = new Subject();
    childSubject = null;

    SubjectUtil.callAs(parentSubject, new Callable<Void>() {
      public Void call() throws InterruptedException {
        Runnable r = new Runnable() {
          @Override
          public void run() {
            childSubject = SubjectUtil.current();
          }
        };

        Daemon t = new Daemon(r);
        t.start();
        t.join(1000);
        return (Void) null;
      }
    });

    assertEquals(parentSubject, childSubject);
  }

  @Test
  public void testThreadOverride() {
    Subject parentSubject = new Subject();
    childSubject = null;

    SubjectUtil.callAs(parentSubject, new Callable<Void>() {
      public Void call() throws InterruptedException {

        Thread t = new Thread() {
          @Override
          public void run() {
            childSubject = SubjectUtil.current();
          }
        };
        t.start();
        t.join(1000);
        return (Void) null;
      }
    });

    if (SubjectUtil.THREAD_INHERITS_SUBJECT) {

      assertEquals(parentSubject, childSubject);
    } else {
      // This is the behaviour that breaks Hadoop authorization
      // This would fail for Java 22-23 if the SecurityManager would be enabled,
      // but we don't run tests with the SecurityManager enabled.
      assertNull(childSubject);
    }
  }

  @Test
  public void testThreadRunnable() {
    Subject parentSubject = new Subject();
    childSubject = null;

    SubjectUtil.callAs(parentSubject, new Callable<Void>() {
      public Void call() throws InterruptedException {
        Runnable r = new Runnable() {
          @Override
          public void run() {
            childSubject = SubjectUtil.current();
          }
        };

        Thread t = new Thread(r);
        t.start();
        t.join(1000);
        return (Void) null;
      }
    });

    if (SubjectUtil.THREAD_INHERITS_SUBJECT) {
      assertEquals(parentSubject, childSubject);
    } else {
      // This is the behaviour that breaks Hadoop authorization
      // This would fail for Java 22-23 if the SecurityManager would be enabled,
      // but we don't run tests with the SecurityManager enabled.
      assertNull(childSubject);
    }
  }

  /**
   * Reproduces the typical usage pattern in long-running JVMs such as Apache Spark:
   * the application enters a Subject scope via {@code Subject.callAs}, then submits tasks
   * to a long-lived thread pool whose worker threads are
   * {@link SubjectInheritingThread}s created (and started) by the pool's internal
   * {@code addWorker} method. The Subject visible inside the task body must be the
   * parent's Subject, otherwise downstream Hadoop calls that read it via
   * {@code UserGroupInformation.getCurrentUser} fall back to the JVM login UGI and lose
   * the delegation tokens that were added under the parent Subject.
   * <p>
   * Regression test for the JDK 22+ failure mode where capturing the Subject inside
   * the {@code final start()} method silently fails when the start is dispatched
   * through {@code ThreadPoolExecutor.addWorker} →
   * {@code jdk.internal.vm.ThreadContainer.start(Thread)}. The current implementation
   * captures the Subject at constructor time in a {@code final} field, which avoids
   * the problematic code path entirely.
   */
  @Test
  public void testSubjectInheritingThreadInThreadPool() throws Exception {
    Subject parentSubject = new Subject();
    AtomicReference<Subject> seenInsidePool = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(2,
        r -> new SubjectInheritingThread(r));
    try {
      SubjectUtil.callAs(parentSubject, () -> {
        pool.submit(() -> {
          seenInsidePool.set(SubjectUtil.current());
        }).get(5, TimeUnit.SECONDS);
        return null;
      });
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertEquals(parentSubject, seenInsidePool.get(),
        "Pool worker must see the parent's Subject when started from a callAs scope");
  }

  /**
   * Variant of {@link #testSubjectInheritingThreadInThreadPool()} for {@link Daemon},
   * which is used by Hadoop's own thread-pool sites (e.g. {@code DelegationTokenRenewer},
   * IPC {@code Client.Connection}, etc.).
   */
  @Test
  public void testDaemonInThreadPool() throws Exception {
    Subject parentSubject = new Subject();
    AtomicReference<Subject> seenInsidePool = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Daemon(r));
    try {
      SubjectUtil.callAs(parentSubject, () -> {
        pool.submit(() -> {
          seenInsidePool.set(SubjectUtil.current());
        }).get(5, TimeUnit.SECONDS);
        return null;
      });
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertEquals(parentSubject, seenInsidePool.get(),
        "Daemon pool worker must see the parent's Subject when started from a callAs scope");
  }

  /**
   * Reproduces the chained-pool pattern: the executor's main thread submits to its
   * dispatcher pool, the dispatcher worker (already a SubjectInheritingThread) then
   * submits to the task thread pool. Both hops must preserve the parent Subject.
   */
  @Test
  public void testSubjectInheritingThreadChainedPools() throws Exception {
    Subject parentSubject = new Subject();
    AtomicReference<Subject> seenInLeafTask = new AtomicReference<>();

    ExecutorService outer = Executors.newFixedThreadPool(1,
        r -> new SubjectInheritingThread(r));
    ExecutorService inner = Executors.newFixedThreadPool(1,
        r -> new SubjectInheritingThread(r));
    try {
      SubjectUtil.callAs(parentSubject, () -> {
        outer.submit(() -> {
          inner.submit(() -> {
            seenInLeafTask.set(SubjectUtil.current());
          }).get(5, TimeUnit.SECONDS);
          return null;
        }).get(5, TimeUnit.SECONDS);
        return null;
      });
    } finally {
      outer.shutdownNow();
      inner.shutdownNow();
      outer.awaitTermination(5, TimeUnit.SECONDS);
      inner.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertEquals(parentSubject, seenInLeafTask.get(),
        "Subject must propagate through chained SubjectInheritingThread pools");
  }

  /**
   * Verifies that the Subject is captured at construction time, NOT at start() time.
   * This matches the pre-JDK22 JVM semantics, where the Subject was propagated to new
   * threads via {@code InheritableThreadLocal}, which is snapshotted at
   * {@code Thread.<init>} (i.e. on the constructing thread). A standalone probe against
   * plain {@code Thread} on JDK 21 confirms the same observation:
   * <pre>
   *   construct in doAs(A), start in doAs(A)    -> observed=A
   *   construct in doAs(A), start in doAs(B)    -> observed=A
   *   construct in doAs(A), start outside       -> observed=A
   *   construct outside,    start in doAs(A)    -> observed=null
   *   construct in doAs(B), start in doAs(A)    -> observed=B
   * </pre>
   * SubjectInheritingThread should produce the same answers on all JDKs.
   */
  @Test
  public void testCaptureHappensAtConstructionNotAtStart() throws Exception {
    Subject subjectA = new Subject();
    Subject subjectB = new Subject();
    AtomicReference<Subject> observed = new AtomicReference<>();
    ArrayBlockingQueue<Thread> handoff = new ArrayBlockingQueue<>(1);

    Runnable body = () -> observed.set(SubjectUtil.current());

    // construct in doAs(A), start in doAs(B) -> observed must be A.
    SubjectUtil.callAs(subjectA, () -> {
      Thread t = new SubjectInheritingThread(body, "construct-A-start-B");
      handoff.put(t);
      return null;
    });
    Thread t1 = handoff.take();
    SubjectUtil.callAs(subjectB, () -> {
      t1.start();
      t1.join(5_000);
      return null;
    });
    assertEquals(subjectA, observed.get(),
        "construct-in-A / start-in-B must observe A (capture at construction)");

    // construct outside any callAs scope, start in doAs(A) -> observed must be null.
    observed.set(null);
    Thread t2 = new SubjectInheritingThread(body, "construct-none-start-A");
    SubjectUtil.callAs(subjectA, () -> {
      t2.start();
      t2.join(5_000);
      return null;
    });
    assertNull(observed.get(),
        "construct-outside-any-scope / start-in-A must observe null (capture at construction)");

    // construct in doAs(B), start in doAs(A) -> observed must be B.
    observed.set(null);
    SubjectUtil.callAs(subjectB, () -> {
      Thread t = new SubjectInheritingThread(body, "construct-B-start-A");
      handoff.put(t);
      return null;
    });
    Thread t3 = handoff.take();
    SubjectUtil.callAs(subjectA, () -> {
      t3.start();
      t3.join(5_000);
      return null;
    });
    assertEquals(subjectB, observed.get(),
        "construct-in-B / start-in-A must observe B (capture at construction)");
  }

  /**
   * Mirror of {@link #testCaptureHappensAtConstructionNotAtStart()} for Daemon.
   */
  @Test
  public void testDaemonCaptureHappensAtConstructionNotAtStart() throws Exception {
    Subject subjectA = new Subject();
    Subject subjectB = new Subject();
    AtomicReference<Subject> observed = new AtomicReference<>();
    ArrayBlockingQueue<Daemon> handoff = new ArrayBlockingQueue<>(1);

    Runnable body = () -> observed.set(SubjectUtil.current());

    // construct in doAs(A), start in doAs(B) -> observed must be A.
    SubjectUtil.callAs(subjectA, () -> {
      Daemon d = new Daemon(body);
      d.setName("daemon-construct-A-start-B");
      handoff.put(d);
      return null;
    });
    Daemon d1 = handoff.take();
    SubjectUtil.callAs(subjectB, () -> {
      d1.start();
      d1.join(5_000);
      return null;
    });
    assertEquals(subjectA, observed.get(),
        "Daemon construct-in-A / start-in-B must observe A (capture at construction)");

    // construct in doAs(B), start in doAs(A) -> observed must be B.
    observed.set(null);
    SubjectUtil.callAs(subjectB, () -> {
      Daemon d = new Daemon(body);
      d.setName("daemon-construct-B-start-A");
      handoff.put(d);
      return null;
    });
    Daemon d2 = handoff.take();
    SubjectUtil.callAs(subjectA, () -> {
      d2.start();
      d2.join(5_000);
      return null;
    });
    assertEquals(subjectB, observed.get(),
        "Daemon construct-in-B / start-in-A must observe B (capture at construction)");
  }

}
