package io.sustc.service.impl.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Runs a group of named tasks on a bounded pool and fails fast on the first error. */
final class ParallelTasks {

    /** A unit of work; may throw checked exceptions such as {@link java.sql.SQLException}. */
    @FunctionalInterface
    interface Task {
        void run() throws Exception;
    }

    record NamedTask(String name, Task task) {}

    private ParallelTasks() {}

    /**
     * Runs all tasks and waits for them. On the first failure the remaining tasks are cancelled
     * and an {@link ImportException} naming the failed task is thrown. Cancelled tasks may still
     * be finishing a JDBC call; callers that clean up afterwards should shut the pool down and
     * await its termination first.
     */
    static void runAll(ExecutorService pool, List<NamedTask> tasks) {
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(pool);
        List<Future<Void>> futures = new ArrayList<>(tasks.size());
        for (NamedTask t : tasks) {
            futures.add(completion.submit(() -> {
                try {
                    t.task().run();
                } catch (Exception e) {
                    throw new ImportException("Import task '" + t.name() + "' failed", e);
                }
                return null;
            }));
        }
        try {
            for (int i = 0; i < futures.size(); i++) {
                completion.take().get();
            }
        } catch (ExecutionException e) {
            futures.forEach(f -> f.cancel(true));
            throw e.getCause() instanceof ImportException ie
                    ? ie
                    : new ImportException("Import task failed", e.getCause());
        } catch (CancellationException e) {
            futures.forEach(f -> f.cancel(true));
            throw new ImportException("Import task was cancelled", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(f -> f.cancel(true));
            throw new ImportException("Interrupted while waiting for import tasks", e);
        }
    }
}
