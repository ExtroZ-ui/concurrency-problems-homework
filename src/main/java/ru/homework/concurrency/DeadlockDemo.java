package ru.homework.concurrency;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DeadlockDemo {
    // Distinct classes make resource names visible in jstack's monitor descriptions.
    private static final class ResourceA {}
    private static final class ResourceB {}

    public static Thread[] start(boolean daemon) {
        Object resourceA = new ResourceA();
        Object resourceB = new ResourceB();
        CountDownLatch bothOwnFirst = new CountDownLatch(2);
        Thread first = new Thread(() -> {
            synchronized (resourceA) {
                System.out.println("deadlock-1 owns resourceA; requesting resourceB");
                rendezvous(bothOwnFirst);
                synchronized (resourceB) { System.out.println("Unreachable: task 1 finished"); }
            }
        }, "deadlock-1");
        Thread second = new Thread(() -> {
            synchronized (resourceB) {
                System.out.println("deadlock-2 owns resourceB; requesting resourceA");
                rendezvous(bothOwnFirst);
                synchronized (resourceA) { System.out.println("Unreachable: task 2 finished"); }
            }
        }, "deadlock-2");
        first.setDaemon(daemon);
        second.setDaemon(daemon);
        first.start();
        second.start();
        return new Thread[] {first, second};
    }

    private static void rendezvous(CountDownLatch gate) {
        gate.countDown();
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Peer did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    public static void assertDetected(Thread[] threads) throws InterruptedException {
        var bean = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long[] ids = bean.findDeadlockedThreads();
            if (ids != null && Arrays.stream(threads).allMatch(t -> Arrays.stream(ids).anyMatch(id -> id == t.threadId()))) {
                for (Thread thread : threads) {
                    var info = bean.getThreadInfo(thread.threadId());
                    if (info.getThreadState() != Thread.State.BLOCKED) throw new AssertionError("Expected BLOCKED");
                    System.out.printf("DETECTED: %s state=%s waits=%s owner=%s%n", info.getThreadName(),
                            info.getThreadState(), info.getLockName(), info.getLockOwnerName());
                }
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected deadlock was not detected");
    }

    public static void run() throws InterruptedException {
        System.out.println("PID=" + ProcessHandle.current().pid());
        Thread[] threads = start(false);
        assertDetected(threads);
        System.out.println("READY_FOR_JSTACK: process deliberately hangs; use capture.cmd or Ctrl+C");
        for (Thread thread : threads) thread.join();
    }

    public static void fixed() throws Exception {
        Object resourceA = new ResourceA();
        Object resourceB = new ResourceB();
        int[] operations = {0};
        Demos.Workers workers = new Demos.Workers();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 1; i <= 2; i++) workers.add("ordered-" + i, () -> {
            start.await();
            for (int n = 0; n < 1000; n++) {
                // Every path uses A -> B. There can be no circular wait.
                synchronized (resourceA) {
                    synchronized (resourceB) { operations[0]++; }
                }
            }
        });
        start.countDown();
        workers.finish();
        Demos.check(operations[0] == 2000, "Ordered locking completed both tasks");
        System.out.println("DEADLOCK FIX: completed=2000; both workers terminated");
    }
}
