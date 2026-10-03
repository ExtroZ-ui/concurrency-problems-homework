package ru.homework.concurrency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public final class Demos {
    @FunctionalInterface interface Job { void run() throws Exception; }

    static final class Workers {
        private final List<Thread> threads = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread add(String name, Job job) {
            Thread thread = new Thread(() -> {
                try { job.run(); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
            }, name);
            // A failed verification should not leave an indefinitely hanging JVM.
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
            return thread;
        }
        void finish() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (Thread thread : threads) {
                long left = deadline - System.nanoTime();
                if (left > 0) thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
            if (threads.stream().anyMatch(Thread::isAlive)) {
                threads.forEach(Thread::interrupt);
                throw new AssertionError("Workers failed to terminate within 10 seconds");
            }
            if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void livelock() throws Exception {
        ReentrantLock resourceA = new ReentrantLock();
        ReentrantLock resourceB = new ReentrantLock();
        CyclicBarrier phase = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        Workers workers = new Workers();
        for (int i = 0; i < 2; i++) {
            ReentrantLock own = i == 0 ? resourceA : resourceB;
            ReentrantLock other = i == 0 ? resourceB : resourceA;
            workers.add("livelock-" + (i + 1), () -> {
                // 100 rounds are an observation limit, not an algorithmic fix.
                for (int round = 0; round < 100; round++) {
                    own.lockInterruptibly();
                    try {
                        phase.await(5, TimeUnit.SECONDS); // both have their first resource
                        attempts.incrementAndGet();
                        if (other.tryLock()) {
                            try { completed.incrementAndGet(); }
                            finally { other.unlock(); }
                        }
                        phase.await(5, TimeUnit.SECONDS); // both attempts failed before release
                    } finally { own.unlock(); }
                    phase.await(5, TimeUnit.SECONDS); // release, retry symmetrically
                }
            });
        }
        workers.finish();
        check(attempts.get() == 200 && completed.get() == 0, "Repeated activity without useful progress");
        System.out.printf("LIVELOCK: rounds=100 attempts=%d completed=%d; stopped by observation limit%n",
                attempts.get(), completed.get());
    }

    public static void livelockFixed() throws Exception {
        ReentrantLock resourceA = new ReentrantLock(true);
        ReentrantLock resourceB = new ReentrantLock(true);
        AtomicInteger completed = new AtomicInteger();
        Workers workers = new Workers();
        for (int i = 1; i <= 2; i++) workers.add("livelock-fixed-" + i, () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int ownCompleted = 0;
            while (ownCompleted < 100) {
                if (System.nanoTime() >= deadline) throw new TimeoutException("Total retry budget exceeded");
                // Common order removes symmetric collisions; waits and total retries are bounded.
                if (!resourceA.tryLock(50, TimeUnit.MILLISECONDS)) continue;
                try {
                    if (!resourceB.tryLock(50, TimeUnit.MILLISECONDS)) continue;
                    try { completed.incrementAndGet(); ownCompleted++; }
                    finally { resourceB.unlock(); }
                } finally { resourceA.unlock(); }
            }
        });
        workers.finish();
        check(completed.get() == 200, "Livelock fix makes useful progress");
        System.out.println("LIVELOCK FIX: completed=200; ordered timed locks, total deadline=5s");
    }

    /** Explicit priority admission: low requests wait while high-priority backlog persists. */
    private static final class PriorityResource {
        final ReentrantLock gate = new ReentrantLock();
        final Condition changed = gate.newCondition();
        final CountDownLatch lowWaiting = new CountDownLatch(1);
        boolean highBacklog = true;
        boolean occupied;

        void acquire(boolean high) throws InterruptedException {
            gate.lockInterruptibly();
            try {
                while (occupied || (!high && highBacklog)) {
                    if (!high) lowWaiting.countDown();
                    changed.await();
                }
                occupied = true;
            } finally { gate.unlock(); }
        }
        void release() {
            gate.lock();
            try { occupied = false; changed.signalAll(); }
            finally { gate.unlock(); }
        }
        void clearBacklog() {
            gate.lock();
            try { highBacklog = false; changed.signalAll(); }
            finally { gate.unlock(); }
        }
    }

    public static void starvation() throws Exception {
        PriorityResource resource = new PriorityResource();
        AtomicBoolean flood = new AtomicBoolean(true);
        AtomicInteger favored = new AtomicInteger();
        AtomicInteger victim = new AtomicInteger();
        CountDownLatch observation = new CountDownLatch(1);
        CountDownLatch snapshotDone = new CountDownLatch(1);
        Workers workers = new Workers();
        Thread low = workers.add("starved-low", () -> {
            resource.acquire(false);
            try { victim.incrementAndGet(); }
            finally { resource.release(); }
        });
        check(resource.lowWaiting.await(5, TimeUnit.SECONDS), "Low-priority request entered waiting");
        workers.add("favored-high", () -> {
            while (flood.get()) {
                resource.acquire(true);
                try {
                    favored.incrementAndGet();
                } finally { resource.release(); }
                if (favored.get() == 1000) {
                    observation.countDown();
                    // Pause the producer for a stable snapshot; priority backlog remains set.
                    snapshotDone.await();
                }
            }
        });
        try {
            check(observation.await(5, TimeUnit.SECONDS), "Favored task progresses");
            check(victim.get() == 0, "Low-priority request starves while high backlog exists");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (low.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
            check(low.getState() == Thread.State.WAITING, "Victim is waiting on the policy condition");
            System.out.printf("STARVATION: highCompleted=%d lowCompleted=%d lowState=%s%n",
                    favored.get(), victim.get(), low.getState());
        } finally {
            flood.set(false);
            snapshotDone.countDown();
            resource.clearBacklog(); // end observation, allow low to finish, join all workers
            workers.finish();
        }
        check(victim.get() == 1, "Victim completed after priority backlog removed");
        System.out.println("STARVATION cleanup: backlog removed, lowCompleted=1; both workers terminated");
    }

    public static void starvationFixed() throws Exception {
        ReentrantLock fairResource = new ReentrantLock(true);
        CountDownLatch start = new CountDownLatch(1);
        int[] counts = new int[4];
        Workers workers = new Workers();
        for (int i = 0; i < counts.length; i++) {
            final int index = i;
            Thread thread = workers.add("fair-worker-" + i, () -> {
                start.await();
                for (int n = 0; n < 300; n++) {
                    fairResource.lockInterruptibly();
                    try { counts[index]++; }
                    finally { fairResource.unlock(); }
                }
            });
            thread.setPriority(Thread.NORM_PRIORITY);
        }
        start.countDown();
        workers.finish();
        check(Arrays.stream(counts).allMatch(n -> n == 300), "Every contender makes progress");
        System.out.println("STARVATION FIX: fair lock, equal priorities, per-worker=" + Arrays.toString(counts));
    }
}
