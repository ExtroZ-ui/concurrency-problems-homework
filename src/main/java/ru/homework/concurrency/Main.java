package ru.homework.concurrency;

public final class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "all" : args[0];
        if (args.length > 1) throw new IllegalArgumentException("Provide a single mode; see help");
        switch (mode) {
            case "deadlock" -> DeadlockDemo.run();
            case "deadlock-fixed" -> DeadlockDemo.fixed();
            case "livelock" -> Demos.livelock();
            case "livelock-fixed" -> Demos.livelockFixed();
            case "starvation" -> Demos.starvation();
            case "starvation-fixed" -> Demos.starvationFixed();
            case "all" -> runFiniteDemos();
            case "verify" -> {
                // Daemon flag is only for test-process cleanup; both threads really deadlock.
                DeadlockDemo.assertDetected(DeadlockDemo.start(true));
                runFiniteDemos();
                System.out.println("PASS: all six scenarios verified; finite workers terminated");
            }
            case "help", "--help" -> System.out.println("Modes: all | deadlock | deadlock-fixed | livelock | " +
                    "livelock-fixed | starvation | starvation-fixed | verify | help");
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private static void runFiniteDemos() throws Exception {
        DeadlockDemo.fixed();
        Demos.livelock();
        Demos.livelockFixed();
        Demos.starvation();
        Demos.starvationFixed();
    }
}
